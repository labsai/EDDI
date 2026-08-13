import { useCallback } from "react";
import { create } from "zustand";
import {
  startConversation,
  sendMessageStreaming,
  type ChatMessage,
  type SSEEvent,
} from "@/lib/api/chat";
import { getSimpleConversationLog, extractOutputParts } from "@/lib/api/conversations";
import { buildAttachmentContext } from "@/lib/api/attachments";
import type { SentAttachment } from "@/hooks/use-chat";
import { resumeConversation, type HitlVerdict, type ToolCallDecision } from "@/lib/api/hitl";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import type { OperatorConfig } from "@/lib/api/operator";
import { getErrorMessage, isApiError } from "@/lib/api-client";

/**
 * Chat state for the Platform Operator.
 *
 * A shared Zustand store, not local component state: the operator is reachable
 * from both the dedicated /manage/operator page and a docked drawer mountable
 * from anywhere in the app, and both must render the SAME live conversation —
 * not two independently-tracked ones. `useOperatorChat` below stays a thin
 * per-field-selecting wrapper, so every existing call site is unaffected.
 *
 * Still its own store rather than the global chat/debug stores: the operator
 * conversation is a separate thing from whatever the user is testing on the
 * Chat page, and mixing the two would show operator tool calls in the
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
   * Lives in the same store as `messages`, not a side map keyed by
   * conversation id: it names one of THOSE messages, and every place that
   * changes one changes both in the same `set()` call, so the two move
   * together or not at all — a decision built any other way could commit
   * `messages` without this (or vice versa) if two surfaces raced.
   */
  pausedPlaceholderId: string | null;
}

/**
 * Store-only fields — never returned by `useOperatorChat`.
 *
 * Promoted from `useRef`s: a shared store has no per-mount instance to hang a
 * ref off, and these need the same get/set access as everything else here.
 * `pausedAt` is deliberately not this field's name — `OperatorChatProps`
 * already has an unrelated `pausedAt` (sourced from `approval-status`, not
 * this).
 */
interface OperatorChatInternal {
  abortController: AbortController | null;
  resolveAbortController: AbortController | null;
  /** `hitlPausedAt` of the pause currently on screen — see pollUntilSettled. */
  decidedPausedAt: string | null;
}

interface OperatorChatActions {
  send: (
    config: OperatorConfig | null | undefined,
    input: string,
    context?: Record<string, unknown>,
    /** Already-uploaded attachments to forward (context refs) and display. */
    attachments?: SentAttachment[],
  ) => Promise<void>;
  /**
   * Create (or return) the active conversation. Exposed so attachment uploads
   * — which need a conversation to upload INTO — can lazily create it before
   * the first message, exactly the way send() itself does.
   */
  ensureConversation: (config: OperatorConfig | null | undefined) => Promise<string>;
  stop: () => void;
  reset: () => void;
  resolveApproval: (
    verdict: HitlVerdict,
    note?: string,
    toolDecisions?: Record<string, ToolCallDecision>,
  ) => Promise<void>;
  /** Drops a stale `error` without touching anything else — see the drawer,
   *  which calls this on open so an hour-old failure from a different surface
   *  is not the first thing shown. */
  clearError: () => void;
}

type OperatorChatStore = OperatorChatState & OperatorChatInternal & OperatorChatActions;

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

export const useOperatorChatStore = create<OperatorChatStore>((set, get) => ({
  messages: [],
  events: [],
  tracesByMessageId: {},
  isStreaming: false,
  error: null,
  // Resume the tab's conversation so navigating away mid-investigation and
  // back does not silently start a new one. Read once, at store creation —
  // `reset()` below hardcodes null rather than re-reading this, so clearing
  // storage and resetting the chat cannot race each other.
  conversationId: readStoredConversationId(),
  isPaused: false,
  pauseReason: null,
  isResolvingPause: false,
  resolveError: null,
  pausedPlaceholderId: null,
  abortController: null,
  resolveAbortController: null,
  decidedPausedAt: null,

  clearError: () => set({ error: null }),

  reset: () => {
    get().abortController?.abort();
    get().resolveAbortController?.abort();
    storeConversationId(null);
    set({
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
      abortController: null,
      resolveAbortController: null,
      decidedPausedAt: null,
    });
  },

  stop: () => {
    get().abortController?.abort();
    set({ abortController: null, isStreaming: false });
  },

  ensureConversation: async (config) => {
    const existing = get().conversationId;
    if (existing) return existing;
    if (!config?.agentId) throw new Error("Operator is not configured");
    const conversationId = await startConversation(config.environment, config.agentId);
    storeConversationId(conversationId);
    set({ conversationId });
    return conversationId;
  },

  send: async (config, input, context, attachments) => {
    // An attachment-only turn is legitimate (matching the main chat panel) —
    // block only when there is neither text nor a file.
    if (!config?.agentId || (!input.trim() && !attachments?.length)) return;
    // `set` below applies synchronously (unlike React's setState), so this
    // also closes a race a second mounted surface (the drawer, alongside the
    // full page) could otherwise trigger: a second send() invoked before this
    // one yields at its first `await` sees isStreaming already true here and
    // bails, before either has touched the network.
    if (get().isStreaming) return;

    const userMessage: ChatMessage = {
      id: nextId("user"),
      role: "user",
      content: input,
      timestamp: Date.now(),
      // Chips/thumbnails on the sent bubble — same display contract as the
      // main chat panel's user messages.
      attachments: attachments?.length
        ? attachments.map((a) => ({
            fileName: a.fileName,
            mimeType: a.mimeType,
            sizeBytes: a.sizeBytes,
            previewUrl: a.previewUrl,
            forwardableInline: a.forwardableInline,
          }))
        : undefined,
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

    set((s) => ({
      ...s,
      messages: [...s.messages, userMessage, agentPlaceholder],
      events: [],
      isStreaming: true,
      error: null,
    }));

    const controller = new AbortController();
    set({ abortController: controller });

    // Read once, here, before the try: the catch block (the 409 branch) needs
    // this id to look the pause reason up, and a reset() that runs while this
    // send is in flight must not erase it out from under that lookup — which a
    // get() call AT the lookup site, instead of this one read, would.
    let conversationId = get().conversationId;

    try {
      if (!conversationId) {
        conversationId = await get().ensureConversation(config);
      }

      // Merge attachment_* refs into the turn context so the backend forwards
      // the uploaded files to the model (same contract as the chat panel).
      const turnContext: Record<string, unknown> = { ...(context ?? {}) };
      if (attachments?.length) {
        Object.assign(turnContext, buildAttachmentContext(attachments));
      }

      const stream = sendMessageStreaming(
        config.environment,
        config.agentId,
        conversationId,
        Object.keys(turnContext).length ? { input, context: turnContext } : { input },
        controller.signal,
      );

      for await (const event of stream) {
        if (event.type === "token") {
          set((s) => ({
            ...s,
            messages: s.messages.map((m) =>
              m.id === agentId ? { ...m, content: m.content + event.data } : m,
            ),
          }));
          continue;
        }
        if (event.type === "error") {
          set((s) => ({ ...s, error: event.data || "Stream error" }));
          continue;
        }
        if (event.type === "done") {
          // The turn's own outcome, including a pause, lives in this snapshot —
          // discarding it (as this used to) meant a turn that paused mid-stream
          // left the input enabled with no indication anything needed a decision.
          //
          // The final output text is extracted for BOTH branches below: the pause
          // path back-fills the pending message from it, and the failure check
          // needs it because a turn can answer entirely through the snapshot —
          // no token frames at all — and an earlier recoverable task_failed must
          // not overwrite that answer with an error.
          let finalText = "";
          if (event.data) {
            try {
              const snapshot: {
                conversationState?: string;
                hitlPauseReason?: string;
                hitlPausedAt?: string;
                conversationOutputs?: Record<string, unknown>[];
              } = JSON.parse(event.data);
              const outputs = snapshot.conversationOutputs ?? [];
              const lastOutput = outputs[outputs.length - 1];
              const parts = lastOutput ? extractOutputParts(lastOutput) : [];
              finalText = parts.join("\n\n");
              if (snapshot.conversationState === "AWAITING_HUMAN") {
                const pendingText = finalText;
                set((s) => ({
                  ...s,
                  isPaused: true,
                  pauseReason: snapshot.hitlPauseReason ?? null,
                  resolveError: null,
                  decidedPausedAt: snapshot.hitlPausedAt ?? null,
                  // This turn's own bubble is the placeholder resolveApproval
                  // will replace — recorded by id, whether or not it ever got
                  // any text.
                  pausedPlaceholderId: agentId,
                  // The backend writes its pending message into this same
                  // step's output, exactly like an ordinary answer — snap the
                  // bubble to it, so a turn that paused after streaming interim
                  // commentary rests on the pending message (what a reload
                  // shows), and one that never streamed shows why it paused
                  // instead of an empty bubble.
                  messages: pendingText
                    ? s.messages.map((m) =>
                        m.id === agentId && m.content !== pendingText ? { ...m, content: pendingText } : m,
                      )
                    : s.messages,
                }));
              }
            } catch {
              // Non-JSON done payload — nothing to inspect, same as before.
            }
          }
          // A turn can fail WITHOUT a stream-level error event: the backend
          // reports the failing step as task_failed, streams no tokens, and
          // closes the stream normally. That left an empty agent bubble and no
          // explanation anywhere in the chat — the admin had to open the server
          // log to learn the turn had failed at all (observed with a provider
          // rejecting the stored LLM config: "`temperature` is deprecated").
          // If nothing was streamed, nothing paused, nothing arrived in the done
          // snapshot, and a step failed, say so where the answer should have
          // been. A snapshot that DOES carry the answer back-fills the bubble
          // instead — the turn recovered, and an error banner over a visible
          // answer would be a lie.
          set((s) => {
            const bubble = s.messages.find((m) => m.id === agentId);
            if (s.isPaused || s.error) {
              return s;
            }
            if (finalText) {
              // Snap to the snapshot's canonical text. Tool-enabled turns now
              // stream every model round live, so interim commentary ("Let me
              // check…") can precede the final answer in the bubble — the
              // stored transcript keeps only the final answer, and the resting
              // bubble must match what a reload would show.
              if (bubble && bubble.content !== finalText) {
                return {
                  ...s,
                  messages: s.messages.map((m) => (m.id === agentId ? { ...m, content: finalText } : m)),
                };
              }
              return s;
            }
            if (bubble?.content.trim()) {
              return s;
            }
            const failure = [...s.events].reverse().find((e) => e.type === "task_failed");
            if (!failure) {
              return s;
            }
            const detail = failure.errorSummary
              ? ` ${failure.errorSummary}`
              : " The server log has the full error.";
            return {
              ...s,
              error: `The operator could not answer — the ${failure.taskType.replace("ai.labs.", "")} step failed.${detail}`,
            };
          });
          break;
        }

        const pipelineEvent = toPipelineEvent(event);
        if (pipelineEvent) {
          set((s) => ({ ...s, events: [...s.events, pipelineEvent] }));
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
        set((s) => ({
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
            set((s) => ({
              ...s,
              pauseReason: snapshot.hitlPauseReason ?? null,
              decidedPausedAt: snapshot.hitlPausedAt ?? null,
            }));
          } catch {
            // Best effort — the pause itself is already surfaced, and a reason
            // we could not read is strictly less bad than no pause indicator.
          }
        }
      } else {
        set((s) => ({ ...s, error: getErrorMessage(error) }));
      }
    } finally {
      // A stopped-then-resent turn can settle *after* its successor started.
      // Such a turn owns only its own message: touching the shared state would
      // null the live controller, wipe the new turn's events, and file them
      // under this turn's message id.
      const isStillCurrent = get().abortController === controller;
      set((s) => ({
        ...s,
        ...(isStillCurrent
          ? {
              abortController: null,
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
  resolveApproval: async (verdict, note, toolDecisions) => {
    const conversationId = get().conversationId;
    if (!conversationId) return;

    const controller = new AbortController();
    set({ isResolvingPause: true, resolveError: null, resolveAbortController: controller });

    try {
      await resumeConversation(conversationId, { verdict, note, toolDecisions });
      const snapshot = await pollUntilSettled(conversationId, controller.signal, get().decidedPausedAt);
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

      // Minted out here, not inside the updater below: even though this
      // store's updater runs exactly once (unlike a React state updater, which
      // StrictMode may invoke twice or invoke and discard), keeping `nextId()`
      // and `Date.now()` out of it keeps the updater a pure projection of
      // `(state) -> state` — the property that makes it safe to reason about,
      // and safe if this store is ever wrapped with logging or time-travel
      // middleware later.
      const newBubbles: ChatMessage[] = parts.map((part) => ({
        id: nextId("agent"),
        role: "agent" as const,
        content: part,
        timestamp: Date.now(),
      }));

      set((s) => {
        const settled = {
          isPaused: rePaused,
          pauseReason: rePaused ? (snapshot.hitlPauseReason ?? null) : null,
          isResolvingPause: false,
          decidedPausedAt: rePaused ? (snapshot.hitlPausedAt ?? null) : null,
        };
        if (newBubbles.length === 0) {
          return { ...s, ...settled, pausedPlaceholderId: null };
        }
        // Read from `s`, never from an outer closure: `messages` here must be
        // this exact update's starting point, not whatever render happened to
        // trigger the call.
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
        set({ isResolvingPause: false, resolveError: getErrorMessage(error) });
      }
    } finally {
      if (get().resolveAbortController === controller) {
        set({ resolveAbortController: null });
      }
    }
  },
}));

export function useOperatorChat(config: OperatorConfig | null | undefined) {
  // Selected individually, not as one object: this store is shared across
  // however many surfaces mount it (today, the full page and the drawer), and
  // Zustand v5 has no built-in shallow-equality selector — an object selector
  // would re-render every consumer on every unrelated field write (an
  // abortController swap, a token streamed into someone else's turn).
  const messages = useOperatorChatStore((s) => s.messages);
  const events = useOperatorChatStore((s) => s.events);
  const tracesByMessageId = useOperatorChatStore((s) => s.tracesByMessageId);
  const isStreaming = useOperatorChatStore((s) => s.isStreaming);
  const error = useOperatorChatStore((s) => s.error);
  const conversationId = useOperatorChatStore((s) => s.conversationId);
  const isPaused = useOperatorChatStore((s) => s.isPaused);
  const pauseReason = useOperatorChatStore((s) => s.pauseReason);
  const isResolvingPause = useOperatorChatStore((s) => s.isResolvingPause);
  const resolveError = useOperatorChatStore((s) => s.resolveError);
  const pausedPlaceholderId = useOperatorChatStore((s) => s.pausedPlaceholderId);

  const rawSend = useOperatorChatStore((s) => s.send);
  const rawEnsureConversation = useOperatorChatStore((s) => s.ensureConversation);
  const stop = useOperatorChatStore((s) => s.stop);
  const reset = useOperatorChatStore((s) => s.reset);
  const resolveApproval = useOperatorChatStore((s) => s.resolveApproval);
  const clearError = useOperatorChatStore((s) => s.clearError);

  // The actions that need config bound in: resolveApproval only ever needs the
  // conversation id already in the store, same as today.
  const send = useCallback(
    (input: string, context?: Record<string, unknown>, attachments?: SentAttachment[]) =>
      rawSend(config, input, context, attachments),
    [rawSend, config],
  );
  const ensureConversation = useCallback(
    () => rawEnsureConversation(config),
    [rawEnsureConversation, config],
  );

  return {
    messages,
    events,
    tracesByMessageId,
    isStreaming,
    error,
    conversationId,
    isPaused,
    pauseReason,
    isResolvingPause,
    resolveError,
    pausedPlaceholderId,
    send,
    ensureConversation,
    stop,
    reset,
    resolveApproval,
    clearError,
  };
}
