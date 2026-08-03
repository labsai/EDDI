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
  /**
   * Id of the agent bubble showing the pending-approval message, or null when
   * there is none (a 409 pause, whose optimistic bubbles were dropped).
   *
   * State rather than a ref precisely because it must stay consistent with
   * `messages`: it names one of them. Held in a ref, the two are updated by
   * separate mechanisms — and a state updater React invokes twice, or invokes
   * and then discards, would leave the ref naming a bubble that was never
   * committed. Sharing the updater makes them move together or not at all.
   */
  pausedPlaceholderId: string | null;
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
 * Polls the conversation until the decision we just submitted has been acted on.
 *
 * `resumeConversation` returns as soon as the decision is recorded, before the
 * resumed turn's continuation (the model's final answer, or the next gated
 * batch) actually completes — a single re-read immediately after would race it.
 *
 * "Acted on" is NOT simply "no longer AWAITING_HUMAN". A resumed turn may pause
 * AGAIN on a fresh tool batch — the backend permits `maxPausesPerTurn` (default
 * 3), and a multi-step job is expected to use them. Waiting for the state to
 * clear would spin until the timeout on a conversation behaving exactly as
 * intended. So a pause carrying a different `hitlPausedAt` than the one we
 * decided also counts as settled, and the caller renders it as the next pause.
 */
async function pollUntilSettled(
  conversationId: string,
  signal: AbortSignal,
  decidedPausedAt: string | null,
) {
  const deadline = Date.now() + RESOLVE_TIMEOUT_MS;
  for (;;) {
    const snapshot = await getSimpleConversationLog(conversationId, false, true);
    const stillTheSamePause =
      snapshot.conversationState === "AWAITING_HUMAN" &&
      // With no timestamp to compare against, treat any pause as the one we
      // decided — the conservative reading, since claiming a new pause we
      // cannot prove would clear the banner for a decision still outstanding.
      //
      // Reachable only in theory: the backend sets hitlPausedAt unconditionally
      // in the same block that sets AWAITING_HUMAN (Conversation#pauseConversation),
      // so a paused snapshot without one would have to predate that or come from
      // somewhere else entirely. Kept as a fallback rather than an assertion,
      // and deliberately NOT inverted to "any pause is a new pause": that would
      // trade a bounded wait-then-timeout for silently clearing an approval the
      // human has not actually given.
      (decidedPausedAt === null || snapshot.hitlPausedAt === decidedPausedAt);
    if (!stillTheSamePause) return snapshot;
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
    pausedPlaceholderId: null,
  }));
  const abortRef = useRef<AbortController | null>(null);
  const resolveAbortRef = useRef<AbortController | null>(null);
  /** `hitlPausedAt` of the pause currently on screen — see pollUntilSettled. */
  const pausedAtRef = useRef<string | null>(null);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    resolveAbortRef.current?.abort();
    resolveAbortRef.current = null;
    pausedAtRef.current = null;
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
      pausedPlaceholderId: null,
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
      // Built before the updater for the same reason as in resolveApproval —
      // `nextId` and `Date.now()` must not run inside one.
      const agentPlaceholder: ChatMessage = {
        id: nextId("agent"),
        role: "agent",
        content: "",
        timestamp: Date.now(),
        isStreaming: true,
      };
      const agentId = agentPlaceholder.id;

      setState((s) => ({
        ...s,
        messages: [...s.messages, userMessage, agentPlaceholder],
        events: [],
        isStreaming: true,
        error: null,
      }));

      const controller = new AbortController();
      abortRef.current = controller;

      // Declared outside the try so the catch can still read it — the 409 branch
      // needs the id to look the pause reason up.
      let conversationId = state.conversationId;

      try {
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
                  hitlPausedAt?: string;
                  conversationOutputs?: Record<string, unknown>[];
                } = JSON.parse(event.data);
                if (snapshot.conversationState === "AWAITING_HUMAN") {
                  const outputs = snapshot.conversationOutputs ?? [];
                  const lastOutput = outputs[outputs.length - 1];
                  const parts = lastOutput ? extractOutputParts(lastOutput) : [];
                  const pendingText = parts.join("\n\n");
                  pausedAtRef.current = snapshot.hitlPausedAt ?? null;
                  setState((s) => ({
                    ...s,
                    isPaused: true,
                    pauseReason: snapshot.hitlPauseReason ?? null,
                    resolveError: null,
                    // This turn's own bubble is the placeholder resolveApproval
                    // will replace — recorded by id, whether or not it ever got
                    // any text.
                    pausedPlaceholderId: agentId,
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
          //
          // No placeholder of ours survives, so resolveApproval must APPEND the
          // resumed answer rather than replace a bubble that isn't there. This
          // is the common shape after a page reload onto an already-paused
          // conversation.
          setState((s) => ({
            ...s,
            isPaused: true,
            // A fresh pause supersedes any failure from an earlier decision;
            // leaving it set would show the new approval card under a stale
            // "resuming failed" error, matching the streamed path above.
            resolveError: null,
            pausedPlaceholderId: null,
            messages: s.messages.filter((m) => m.id !== userMessage.id && m.id !== agentId),
          }));
          // The pause happened on a turn we never saw, so its reason is not in
          // any snapshot we hold — read it, or the banner shows a bare
          // "awaiting approval" with no explanation of what for.
          if (conversationId) {
            try {
              const snapshot = await getSimpleConversationLog(conversationId, false, true);
              pausedAtRef.current = snapshot.hitlPausedAt ?? null;
              setState((s) => ({ ...s, pauseReason: snapshot.hitlPauseReason ?? null }));
            } catch {
              // Best effort — the pause itself is already surfaced, and a reason
              // we could not read is strictly less bad than no pause indicator.
            }
          }
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
   * completes (the model's final answer, or the next gated batch), so a single
   * re-read immediately after would race it — hence `pollUntilSettled`.
   *
   * Reconciliation is by `pausedPlaceholderId`, not by counting outputs. Both
   * reads available here run with `returnCurrentStepOnly` on — the streamed
   * `done` snapshot by the backend's own default, `getSimpleConversationLog`
   * because every call here passes `true` explicitly (its wrapper defaults to
   * `false`) — and `ConversationMemoryUtilities` collapses
   * `conversationOutputs` to exactly one element in that mode. So the response
   * carries the resumed turn's answer and nothing else, and any "did the step
   * advance?" comparison of those two lengths would be 1 vs 1: an answer that
   * looks computed but is a constant.
   *
   * When we own a placeholder bubble (the pause arrived on a turn we streamed)
   * it is replaced in place; when we do not (a 409 pause, whose optimistic
   * bubbles were dropped) the answer is appended.
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
        const snapshot = await pollUntilSettled(conversationId, controller.signal, pausedAtRef.current);
        // `pollUntilSettled` can only observe an abort between polls — the reads
        // themselves take no signal. So a `reset()` during the wait (the chat's
        // own clear button, or deactivating the operator) leaves this
        // continuation running against a conversation the user has discarded,
        // and without this guard it would write that conversation's answer back
        // into the freshly-emptied transcript and re-raise `isPaused`.
        if (controller.signal.aborted) return;

        const outputs = snapshot.conversationOutputs ?? [];
        const lastOutput = outputs[outputs.length - 1];
        const parts = lastOutput ? extractOutputParts(lastOutput) : [];
        // The resumed turn may have paused again on a fresh tool batch — normal
        // for a multi-step job. Its own pending message is what we just read, so
        // the bubble we render for it becomes the placeholder the NEXT decision
        // replaces.
        const rePaused = snapshot.conversationState === "AWAITING_HUMAN";
        pausedAtRef.current = rePaused ? (snapshot.hitlPausedAt ?? null) : null;

        // Minted out here, not in the updater below: `nextId` and `Date.now()`
        // are side effects, and React may run an updater more than once or run
        // it and discard the result. Ids created inside would differ between
        // invocations, so `pausedPlaceholderId` could end up naming a bubble
        // that was never committed — and the next decision would then fail to
        // find it and append a duplicate instead of replacing it.
        const newBubbles: ChatMessage[] = parts.map((part) => ({
          id: nextId("agent"),
          role: "agent" as const,
          content: part,
          timestamp: Date.now(),
        }));

        setState((s) => {
          const settled = {
            isPaused: rePaused,
            pauseReason: rePaused ? (snapshot.hitlPauseReason ?? null) : null,
            isResolvingPause: false,
          };
          if (newBubbles.length === 0) {
            return { ...s, ...settled, pausedPlaceholderId: null };
          }
          // Read from `s`, never from the enclosing render's state: this
          // callback is only rebuilt when the conversation id changes, so a
          // closure copy of `messages` would be stale by the second decision.
          const placeholderId = s.pausedPlaceholderId;
          const placeholderIdx = placeholderId
            ? s.messages.findIndex((m) => m.id === placeholderId)
            : -1;
          const [first, ...rest] = newBubbles;
          let messages: ChatMessage[];
          let renderedId: string;
          if (placeholderIdx >= 0) {
            // Reuse the placeholder's own id for the first part so any state
            // keyed by it (tracesByMessageId — the trace of the very turn that
            // paused) stays attached to the answer it belongs to.
            messages = [
              ...s.messages.slice(0, placeholderIdx),
              { ...s.messages[placeholderIdx]!, content: first!.content, isStreaming: false },
              ...rest,
              ...s.messages.slice(placeholderIdx + 1),
            ];
            // The LAST bubble rendered, matching the append branch below: on a
            // re-pause this becomes the next placeholder, and a multi-part
            // pending message must leave the next decision replacing its tail
            // rather than overwriting its opening and stranding the remainder.
            renderedId = rest.length > 0 ? rest[rest.length - 1]!.id : placeholderId!;
          } else {
            messages = [...s.messages, ...newBubbles];
            renderedId = newBubbles[newBubbles.length - 1]!.id;
          }
          return {
            ...s,
            ...settled,
            messages,
            pausedPlaceholderId: rePaused ? renderedId : null,
          };
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
