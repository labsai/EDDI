import { useCallback, useRef, useState } from "react";
import {
  startConversation,
  sendMessageStreaming,
  type ChatMessage,
  type SSEEvent,
} from "@/lib/api/chat";
import { getSimpleConversationLog, extractOutputParts } from "@/lib/api/conversations";
import { resumeConversation, type HitlVerdict, type ToolCallDecision } from "@/lib/api/hitl";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import type { OperatorConfig } from "@/lib/api/operator";
import { getErrorMessage, isApiError } from "@/lib/api-client";

/**
 * Chat state for the Platform Operator.
 *
 * Deliberately a local hook rather than the global chat/debug stores: the
 * operator conversation is a separate thing from whatever the user is testing on
 * the Chat page, and mixing the two would show operator tool calls in the
 * agent-debug drawer (and vice versa).
 */

export interface OperatorChatState {
  messages: ChatMessage[];
  /** Pipeline events for the turn in flight, fed straight to `ChatActivity`. */
  events: PipelineEvent[];
  /**
   * Completed turns' traces, keyed by the agent message they belong to.
   *
   * An operator answer is only as trustworthy as the reads behind it, so the
   * trace has to stay attached to its answer rather than being replaced by the
   * next turn's.
   */
  tracesByMessageId: Record<string, PipelineEvent[]>;
  isStreaming: boolean;
  error: string | null;
  conversationId: string | null;
  /** True while the conversation is AWAITING_HUMAN — detected either from the
   *  streamed turn's own `done` snapshot, or from a 409 on `send` (paused by a
   *  previous turn, rejected without being consumed). */
  isPaused: boolean;
  pauseReason: string | null;
  /** True while a submitted decision is being resumed and its continuation
   *  polled for — separate from `isStreaming`, which this deliberately does not
   *  reuse: the SSE connection is not open during this wait. */
  isResolvingPause: boolean;
  /** Set when resuming or polling for the resumed turn's outcome fails. The
   *  pause itself is NOT cleared — the admin can still decide again. */
  resolveError: string | null;
}

/**
 * Where the active operator conversation id is remembered.
 *
 * sessionStorage, not localStorage: an operator investigation belongs to the
 * tab you are working in, and should not resurface weeks later in an unrelated
 * session.
 */
const CONVERSATION_STORAGE_KEY = "eddi.operator.conversationId";

function readStoredConversationId(): string | null {
  try {
    return sessionStorage.getItem(CONVERSATION_STORAGE_KEY);
  } catch {
    // Storage can be unavailable (private mode, blocked cookies). Losing
    // resumption is a downgrade, not a failure.
    return null;
  }
}

function storeConversationId(conversationId: string | null): void {
  try {
    if (conversationId) {
      sessionStorage.setItem(CONVERSATION_STORAGE_KEY, conversationId);
    } else {
      sessionStorage.removeItem(CONVERSATION_STORAGE_KEY);
    }
  } catch {
    // Ignored — see readStoredConversationId.
  }
}

let messageSeq = 0;
function nextId(prefix: string): string {
  messageSeq += 1;
  return `${prefix}-${messageSeq}`;
}

/**
 * Parse an SSE payload into a pipeline event.
 *
 * The backend sends JSON for structured task events but falls back to plain text
 * in some paths, so both are handled — same tolerance as the main chat hook.
 */
function toPipelineEvent(event: SSEEvent): PipelineEvent | null {
  if (event.type !== "task_start" && event.type !== "task_complete" && event.type !== "task_failed") {
    return null;
  }
  let taskId = "unknown";
  let taskType = "unknown";
  let index = 0;
  let durationMs: number | undefined;
  let toolTrace: PipelineEvent["toolTrace"];
  let actions: string[] | undefined;
  let errorType: string | undefined;
  let errorSummary: string | undefined;

  try {
    const parsed = JSON.parse(event.data);
    taskId = parsed.taskId ?? parsed.id ?? "unknown";
    taskType = parsed.taskType ?? parsed.type ?? "unknown";
    index = parsed.index ?? 0;
    durationMs = parsed.durationMs ?? parsed.duration;
    toolTrace = parsed.toolTrace;
    actions = parsed.actions;
    errorType = parsed.errorType;
    errorSummary = parsed.errorSummary;
  } catch {
    taskType = event.data || "unknown";
  }

  return {
    type: event.type,
    taskId,
    taskType,
    index,
    durationMs,
    toolTrace,
    actions,
    errorType,
    errorSummary,
    timestamp: Date.now(),
  };
}

/** How long a decision may take to resolve before pollUntilSettled gives up. */
const RESOLVE_TIMEOUT_MS = 90_000;
/** How often to poll while waiting for a resumed turn to settle. */
const RESOLVE_POLL_INTERVAL_MS = 1_500;

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException("Aborted", "AbortError"));
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    });
  });
}

/**
 * Polls the conversation until it is no longer AWAITING_HUMAN.
 *
 * `resumeConversation` returns as soon as the decision is recorded, before the
 * resumed turn's continuation (the model's final answer, or the next gated
 * batch) actually completes — a single re-read immediately after would race it.
 */
async function pollUntilSettled(conversationId: string, signal: AbortSignal) {
  const deadline = Date.now() + RESOLVE_TIMEOUT_MS;
  for (;;) {
    const snapshot = await getSimpleConversationLog(conversationId, false, true);
    if (snapshot.conversationState !== "AWAITING_HUMAN") return snapshot;
    if (Date.now() >= deadline) {
      throw new Error(
        "Timed out waiting for the resumed turn to finish. It may still complete — refresh in a moment.",
      );
    }
    await sleep(RESOLVE_POLL_INTERVAL_MS, signal);
  }
}

export function useOperatorChat(config: OperatorConfig | null | undefined) {
  const [state, setState] = useState<OperatorChatState>(() => ({
    messages: [],
    events: [],
    tracesByMessageId: {},
    isStreaming: false,
    error: null,
    // Resume the tab's conversation so navigating away mid-investigation and
    // back does not silently start a new one.
    conversationId: readStoredConversationId(),
    isPaused: false,
    pauseReason: null,
    isResolvingPause: false,
    resolveError: null,
  }));
  const abortRef = useRef<AbortController | null>(null);
  const resolveAbortRef = useRef<AbortController | null>(null);
  /** conversationOutputs.length at the moment the pause was detected — see
   *  resolveApproval for why this decides append-vs-replace on reconciliation. */
  const pausedOutputCountRef = useRef(0);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    resolveAbortRef.current?.abort();
    resolveAbortRef.current = null;
    storeConversationId(null);
    setState({
      messages: [],
      events: [],
      tracesByMessageId: {},
      isStreaming: false,
      error: null,
      conversationId: null,
      isPaused: false,
      pauseReason: null,
      isResolvingPause: false,
      resolveError: null,
    });
  }, []);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState((s) => ({ ...s, isStreaming: false }));
  }, []);

  const send = useCallback(
    async (input: string) => {
      if (!config?.agentId || !input.trim()) return;

      const userMessage: ChatMessage = {
        id: nextId("user"),
        role: "user",
        content: input,
        timestamp: Date.now(),
      };
      const agentId = nextId("agent");

      setState((s) => ({
        ...s,
        messages: [
          ...s.messages,
          userMessage,
          {
            id: agentId,
            role: "agent",
            content: "",
            timestamp: Date.now(),
            isStreaming: true,
          },
        ],
        events: [],
        isStreaming: true,
        error: null,
      }));

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        let conversationId = state.conversationId;
        if (!conversationId) {
          conversationId = await startConversation(config.environment, config.agentId);
          storeConversationId(conversationId);
          setState((s) => ({ ...s, conversationId }));
        }

        const stream = sendMessageStreaming(
          config.environment,
          config.agentId,
          conversationId,
          { input },
          controller.signal,
        );

        for await (const event of stream) {
          if (event.type === "token") {
            setState((s) => ({
              ...s,
              messages: s.messages.map((m) =>
                m.id === agentId ? { ...m, content: m.content + event.data } : m,
              ),
            }));
            continue;
          }
          if (event.type === "error") {
            setState((s) => ({ ...s, error: event.data || "Stream error" }));
            continue;
          }
          if (event.type === "done") {
            // The turn's own outcome, including a pause, lives in this snapshot —
            // discarding it (as this used to) meant a turn that paused mid-stream
            // left the input enabled with no indication anything needed a decision.
            if (event.data) {
              try {
                const snapshot: {
                  conversationState?: string;
                  hitlPauseReason?: string;
                  conversationOutputs?: Record<string, unknown>[];
                } = JSON.parse(event.data);
                if (snapshot.conversationState === "AWAITING_HUMAN") {
                  const outputs = snapshot.conversationOutputs ?? [];
                  pausedOutputCountRef.current = outputs.length;
                  const lastOutput = outputs[outputs.length - 1];
                  const parts = lastOutput ? extractOutputParts(lastOutput) : [];
                  const pendingText = parts.join("\n\n");
                  setState((s) => ({
                    ...s,
                    isPaused: true,
                    pauseReason: snapshot.hitlPauseReason ?? null,
                    resolveError: null,
                    // The backend writes its pending message into this same
                    // step's output, exactly like an ordinary answer — back-fill
                    // it the same way a structured-JSON turn is back-filled below,
                    // so a turn that pauses without ever streaming a token still
                    // shows why, not an empty bubble.
                    messages: pendingText
                      ? s.messages.map((m) =>
                          m.id === agentId && !m.content.trim() ? { ...m, content: pendingText } : m,
                        )
                      : s.messages,
                  }));
                }
              } catch {
                // Non-JSON done payload — nothing to inspect, same as before.
              }
            }
            break;
          }

          const pipelineEvent = toPipelineEvent(event);
          if (pipelineEvent) {
            setState((s) => ({ ...s, events: [...s.events, pipelineEvent] }));
          }
        }
      } catch (error) {
        if (controller.signal.aborted) {
          // no-op, matches the pre-existing behavior
        } else if (isApiError(error) && error.status === 409) {
          // The conversation was already AWAITING_HUMAN from an earlier turn —
          // this send was rejected WITHOUT being consumed. Drop the optimistic
          // user message and the empty streaming placeholder (neither happened),
          // and show the pause rather than a raw error bubble.
          setState((s) => ({
            ...s,
            isPaused: true,
            messages: s.messages.filter((m) => m.id !== userMessage.id && m.id !== agentId),
          }));
        } else {
          setState((s) => ({ ...s, error: getErrorMessage(error) }));
        }
      } finally {
        // A stopped-then-resent turn can settle *after* its successor started.
        // Such a turn owns only its own message: touching the shared state would
        // null the live controller, wipe the new turn's events, and file them
        // under this turn's message id.
        const isStillCurrent = abortRef.current === controller;
        if (isStillCurrent) {
          abortRef.current = null;
        }
        setState((s) => ({
          ...s,
          ...(isStillCurrent
            ? {
                isStreaming: false,
                events: [],
                tracesByMessageId:
                  s.events.length > 0
                    ? { ...s.tracesByMessageId, [agentId]: s.events }
                    : s.tracesByMessageId,
              }
            : {}),
          messages: s.messages.map((m) =>
            m.id === agentId ? { ...m, isStreaming: false } : m,
          ),
        }));
      }
    },
    [config, state.conversationId],
  );

  /**
   * Submits a human decision for the paused conversation, then waits for and
   * reconciles the resumed turn's outcome into the transcript.
   *
   * Never blind-appends: `resumeConversation` returns before its continuation
   * completes, and a TOOL_CALL resume re-enters the SAME step the pause
   * interrupted — the backend drops the pending-approval placeholder and
   * appends the final answer to that step's OWN output list, it does not start
   * a new one. So whether the resumed turn produced a NEW step (a RULE pause
   * can advance one) or reused the paused step (the TOOL_CALL case) is read
   * back from `conversationOutputs.length`, captured at pause time, rather than
   * assumed — reconciling by identity (replace the still-showing placeholder)
   * instead of by counting messages sent.
   */
  const resolveApproval = useCallback(
    async (verdict: HitlVerdict, note?: string, toolDecisions?: Record<string, ToolCallDecision>) => {
      const conversationId = state.conversationId;
      if (!conversationId) return;

      setState((s) => ({ ...s, isResolvingPause: true, resolveError: null }));
      const controller = new AbortController();
      resolveAbortRef.current = controller;

      try {
        await resumeConversation(conversationId, { verdict, note, toolDecisions });
        const snapshot = await pollUntilSettled(conversationId, controller.signal);

        const outputs = snapshot.conversationOutputs ?? [];
        const baseline = pausedOutputCountRef.current;
        // A RULE pause CAN commit as a new step; a TOOL_CALL pause never does
        // (LlmTask.executeResume appends to the step it paused in). Reading the
        // actual count, rather than assuming either shape, covers both.
        const isNewStep = outputs.length > baseline;
        const relevantOutputs = isNewStep ? outputs.slice(baseline) : outputs.slice(-1);
        const newBubbles: ChatMessage[] = relevantOutputs
          .flatMap((output) => extractOutputParts(output))
          .map((part) => ({ id: nextId("agent"), role: "agent" as const, content: part, timestamp: Date.now() }));

        setState((s) => {
          let messages = s.messages;
          if (!isNewStep && newBubbles.length > 0) {
            // Same step as the pause: the placeholder bubble showing the pending
            // message is always the last message here (sending is disabled while
            // paused), so it is replaced in place rather than searched for.
            const lastIdx = messages.length - 1;
            const last = messages[lastIdx];
            if (last?.role === "agent") {
              const [first, ...rest] = newBubbles;
              messages = [...messages.slice(0, lastIdx), { ...last, content: first!.content }, ...rest];
            } else {
              messages = [...messages, ...newBubbles];
            }
          } else if (isNewStep) {
            messages = [...messages, ...newBubbles];
          }
          return { ...s, messages, isPaused: false, pauseReason: null, isResolvingPause: false };
        });
      } catch (error) {
        if (!controller.signal.aborted) {
          setState((s) => ({ ...s, isResolvingPause: false, resolveError: getErrorMessage(error) }));
        }
      } finally {
        if (resolveAbortRef.current === controller) {
          resolveAbortRef.current = null;
        }
      }
    },
    [state.conversationId],
  );

  return { ...state, send, stop, reset, resolveApproval };
}
