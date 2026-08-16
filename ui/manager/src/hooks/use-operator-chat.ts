import { useCallback } from "react";
import { operatorEnvironment } from "@/lib/operator/operator-environment";
import { create } from "zustand";
import {
  startConversation,
  sendMessageStreaming,
  type ChatMessage,
  type SSEEvent,
} from "@/lib/api/chat";
import {
  getSimpleConversationLog,
  getConversationDescriptors,
  extractOutputParts,
  extractInput,
  parseConversationUri,
  type ConversationState,
  type SimpleConversationMemorySnapshot,
} from "@/lib/api/conversations";
import { buildAttachmentContext } from "@/lib/api/attachments";
import { revokeMessagePreviews, type SentAttachment } from "@/hooks/use-chat";
import { resumeConversation, type HitlVerdict, type ToolCallDecision } from "@/lib/api/hitl";
import type { PipelineEvent } from "@/hooks/use-debug-events";
import { OPERATOR_PROBE_USER_ID, type OperatorConfig } from "@/lib/api/operator";
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
   * Tool names from live `tool_call` SSE events for the turn in flight, in
   * call order — drives "Using {tool}…" in the status line. Kept separate from
   * `events`: the authoritative record (arguments, results) still arrives in
   * task_complete's toolTrace, and merging both would double-count.
   */
  liveToolCalls: string[];
  /** True once tokens resumed after the last tool_call — the tool phase is over. */
  liveToolsSettled: boolean;
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
   * `hitlPausedAt` of the pause currently on screen, null when not paused.
   *
   * Doubles as the pause's IDENTITY for consumers: `useApprovalStatus` keys its
   * cache on it, so the second pause of a turn fetches ITS calls instead of
   * re-rendering the first pause's decided ones. Internally it is also what
   * `pollUntilSettled` compares against to recognise a NEW pause.
   */
  decidedPausedAt: string | null;
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
  /**
   * True while a stored (or recovered) conversation is being read back.
   *
   * Separate from `isStreaming` for the same reason `isResolvingPause` is: no
   * SSE connection is open, and the composer should stay usable — a restore
   * that is slow or fails must not lock the admin out of starting a new turn.
   */
  isHydrating: boolean;
  /**
   * True when the restored conversation cannot take another turn — `ENDED`,
   * `ERROR`, `EXECUTION_INTERRUPTED`, or a turn still `IN_PROGRESS`.
   *
   * The transcript is still shown (it is what the admin asked for), but the
   * composer closes: sending into any of those fails at the backend, and an
   * enabled composer over a dead conversation is a trap. Cleared by `reset()`,
   * because a new conversation is by definition writable.
   */
  isReadOnly: boolean;
  /** Lifecycle state of the restored conversation, for explaining the above. */
  conversationState: ConversationState | null;
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
  /** In-flight hydrate, if any. Doubles as the "already hydrating" latch. */
  hydrateAbortController: AbortController | null;
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
  /**
   * Restore the transcript of the conversation this tab was last working in.
   *
   * Safe to call from every mounted surface on every mount — see the
   * implementation for the four separate ways it declines to do anything.
   */
  hydrate: (config: OperatorConfig | null | undefined) => Promise<void>;
  /**
   * Make `conversationId` the active conversation and load it, replacing
   * whatever is on screen. The History tab's row click.
   */
  selectConversation: (conversationId: string) => Promise<void>;
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

/**
 * Set when the admin explicitly discarded the conversation ("Start a new
 * conversation"), so recovery does not hand it straight back.
 *
 * Needed because `reset()` clears the stored id, and "no stored id" is the exact
 * signal {@link findLatestOperatorConversation} reads as "fresh tab, restore the
 * newest". Nothing ends the conversation server-side, so it stays `READY` and
 * stays newest — making it the FIRST thing recovery picks. Without this flag,
 * clearing the chat and reopening the drawer brought the whole discarded
 * transcript back, pause included.
 *
 * Stored rather than kept in memory: a reload also leaves no stored id, and the
 * resurrection would simply happen one navigation later.
 *
 * Scoped to the tab like the id itself, and cleared the moment a conversation is
 * deliberately adopted again — a new one via `ensureConversation`, or an old one
 * picked from History.
 */
const RECOVERY_DECLINED_STORAGE_KEY = "eddi.operator.recoveryDeclined";

function readRecoveryDeclined(): boolean {
  try {
    return sessionStorage.getItem(RECOVERY_DECLINED_STORAGE_KEY) === "1";
  } catch {
    // Storage unavailable — see readStoredConversationId. Defaulting to "not
    // declined" only costs an offer to restore, never a lost conversation.
    return false;
  }
}

function storeRecoveryDeclined(declined: boolean): void {
  try {
    if (declined) {
      sessionStorage.setItem(RECOVERY_DECLINED_STORAGE_KEY, "1");
    } else {
      sessionStorage.removeItem(RECOVERY_DECLINED_STORAGE_KEY);
    }
  } catch {
    // Ignored — see readStoredConversationId.
  }
}

/**
 * The one in-flight conversation create, when any — module-level because the
 * store is module-level and every surface shares it. See ensureConversation.
 */
let creatingConversation: Promise<string> | null = null;

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
    // EDDI serializes the failure detail as "error" (RestAgentEngineStreaming
    // task_failed payload); "errorSummary" kept as a fallback for any newer
    // shape. Reading only errorSummary lost the detail on every operator
    // failure — the exact field the failure-UX banner wants to show.
    errorSummary = parsed.error ?? parsed.errorSummary;
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

/** How long a decision may sit unacted-on before pollUntilSettled gives up. */
const RESOLVE_TIMEOUT_MS = 90_000;
/**
 * Ceiling while the resumed turn is OBSERVABLY EXECUTING (`IN_PROGRESS`).
 *
 * A separate, longer limit than {@link RESOLVE_TIMEOUT_MS} on purpose: a turn
 * that chains model calls and tools legitimately runs for minutes (the
 * backend's own streaming backstop alone is 120s per model call), and timing
 * out at 90s while the backend was demonstrably working reported a scary
 * failure over a healthy turn — whose outcome then arrived with nobody
 * polling. 90s stays the cap for "the decision was never acted on".
 */
const RESOLVE_EXECUTING_TIMEOUT_MS = 300_000;
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
 * "Acted on" is NOT simply "no longer AWAITING_HUMAN". Two non-settled shapes
 * exist, and both were live bugs when read as settled:
 *
 * - A resumed turn may pause AGAIN on a fresh tool batch — the backend permits
 *   `maxPausesPerTurn` (default 3), and a multi-step job is expected to use
 *   them. Waiting for the state to clear would spin until the timeout on a
 *   conversation behaving exactly as intended. So a pause carrying a different
 *   `hitlPausedAt` than the one we decided counts as settled, and the caller
 *   renders it as the next pause.
 *
 * - Accepting the resume PERSISTS `IN_PROGRESS` immediately (the
 *   AWAITING_HUMAN→IN_PROGRESS CAS in ConversationHitlService that claims the
 *   decision), while the turn's OUTCOME persists only when it finishes. For the
 *   whole execution the store therefore answers `IN_PROGRESS` with the
 *   PRE-DECISION outputs — the pending-ask text still in place. Reading that as
 *   settled re-rendered the old ask as "the answer" (a byte-identical duplicate
 *   bubble), cleared `isPaused`, and left the NEXT pause with no approval
 *   controls at all: the approved tools ran server-side, the agent was created,
 *   and the screen said nothing happened. `IN_PROGRESS` means the decision is
 *   being acted on RIGHT NOW — the one state that is unambiguously "keep
 *   waiting".
 */
async function pollUntilSettled(
  conversationId: string,
  signal: AbortSignal,
  decidedPausedAt: string | null,
) {
  const start = Date.now();
  for (;;) {
    const snapshot = await getSimpleConversationLog(conversationId, false, true);
    const stillTheSamePause =
      snapshot.conversationState === "AWAITING_HUMAN" &&
      // With no timestamp to compare against, treat any pause as the one we
      // decided — the conservative reading, since claiming a new pause we
      // cannot prove would clear the banner for a decision still outstanding.
      //
      // A theoretical fallback only since resolveApproval started recovering
      // the timestamp from its pre-resume baseline read: a streamed pause used
      // to arrive with null here (the SSE done payload carried no
      // hitlPausedAt), which made every re-pause read as "the same pause" and
      // spun this loop to its full timeout while the next batch sat undecided.
      // Kept, and deliberately NOT inverted to "any pause is a new pause":
      // that would trade a bounded wait-then-timeout for silently clearing an
      // approval the human has not actually given.
      (decidedPausedAt === null || snapshot.hitlPausedAt === decidedPausedAt);
    const stillExecuting = snapshot.conversationState === "IN_PROGRESS";
    if (!stillTheSamePause && !stillExecuting) return snapshot;
    // Two ceilings: a turn we can SEE executing earns the longer one; a pause
    // that never even flips to IN_PROGRESS means the decision was not acted
    // on, and waiting minutes for it would just delay the error.
    const deadline =
      start + (stillExecuting ? RESOLVE_EXECUTING_TIMEOUT_MS : RESOLVE_TIMEOUT_MS);
    if (Date.now() >= deadline) {
      throw new Error(
        "Timed out waiting for the resumed turn to finish. It may still complete — refresh in a moment.",
      );
    }
    await sleep(RESOLVE_POLL_INTERVAL_MS, signal);
  }
}

/**
 * Tool calls the current step has executed, from a DETAILED current-step-only
 * snapshot — name → whether it succeeded.
 *
 * Sourced from the step's `httpCalls` output map, whose shape the backend
 * guarantees: executing a call writes `<name>Request`, and the parsed response
 * object is stored as `<name>_response` (McpApiToolBuilder's
 * `responseObjectName`) ONLY when the call succeeded — failed results are
 * deliberately not merged (ApiCallsTask.isFailureResult). So Request-without-
 * response reads as "ran and failed", which is exactly what the receipt needs
 * to say.
 *
 * Detailed only: the default simple view strips `httpCalls` from outputs, which
 * is why `pollUntilSettled`'s cheap 1.5s polls cannot provide this.
 */
function executedCallsFromDetailed(
  snapshot: SimpleConversationMemorySnapshot,
): Map<string, boolean> {
  const outputs = snapshot.conversationOutputs ?? [];
  const last = outputs[outputs.length - 1] as Record<string, unknown> | undefined;
  const calls = new Map<string, boolean>();
  const httpCalls = last?.["httpCalls"];
  if (httpCalls && typeof httpCalls === "object" && !Array.isArray(httpCalls)) {
    const keys = Object.keys(httpCalls as Record<string, unknown>);
    for (const key of keys) {
      if (key.endsWith("Request")) {
        const name = key.slice(0, -"Request".length);
        if (name) calls.set(name, keys.includes(`${name}_response`));
      }
    }
  }
  return calls;
}

/**
 * Rebuild a transcript from a stored conversation.
 *
 * Step i's input pairs with output i — the same index alignment
 * `use-chat.ts`'s `snapshotToMessages` relies on, and the reason this must be
 * read with `returnCurrentStepOnly: false`: in current-step-only mode the
 * backend collapses `conversationOutputs` to a single element, so every step
 * would pair against the last turn's answer.
 *
 * What deliberately does NOT come back: the client-side `decision` and `notice`
 * entries this tab inserts around an approval. They are this tab's record of
 * what a human did, not backend state, and the server drops its own copy of the
 * ask from a resolved step. A hydrated view is still coherent — the transcript
 * reads request → answer — and reconstructing the decisions would mean
 * asserting, from a transcript that does not say so, that someone approved
 * something.
 */
function snapshotToMessages(snapshot: SimpleConversationMemorySnapshot): ChatMessage[] {
  const messages: ChatMessage[] = [];
  const outputs = snapshot.conversationOutputs ?? [];
  const steps = snapshot.conversationSteps ?? [];
  for (let i = 0; i < steps.length; i++) {
    const step = steps[i];
    const input = step ? extractInput(step) : undefined;
    if (input) {
      messages.push({
        id: nextId("user"),
        role: "user",
        content: input,
        timestamp: Date.now(),
      });
    }
    for (const part of extractOutputParts(outputs[i])) {
      messages.push({
        id: nextId("agent"),
        role: "agent",
        content: part,
        timestamp: Date.now(),
      });
    }
  }
  return messages;
}

/**
 * How many of the operator's conversations to look at when recovering the most
 * recent one. A page, not one row: see {@link findLatestOperatorConversation}.
 */
const RECOVERY_PAGE_SIZE = 20;

/**
 * How many pages of descriptors recovery will read before giving up.
 *
 * Bounded rather than exhaustive: this runs on mount, and an operator with
 * thousands of conversations must not turn a page load into a crawl. 5 × 20 =
 * 100 covers any realistic operator history; beyond that the admin can pick the
 * conversation from the History tab explicitly.
 */
const RECOVERY_MAX_PAGES = 5;

/**
 * The states a conversation can be restored INTO.
 *
 * Same rule `use-chat.ts` states for the main chat, deliberately reproduced
 * rather than loosened: `ENDED` and `ERROR` are terminal and `IN_PROGRESS`
 * means a turn is still executing, so dropping into any of them is worse than a
 * clean start — the composer would be live over a conversation that rejects the
 * next message.
 */
const RESUMABLE_STATES: ReadonlySet<string> = new Set(["READY", "AWAITING_HUMAN"]);

/**
 * Whether there is something on screen that a restore must not overwrite.
 *
 * One predicate, used by hydrate's pre-check AND its post-read re-check, so the
 * two cannot drift apart — the post-read check exists because a `send()` that
 * started mid-read owns the screen, and it is only correct if it asks the same
 * question the pre-check did.
 */
function hasLiveTranscript(state: OperatorChatState): boolean {
  return state.isStreaming || state.messages.length > 0;
}

/**
 * The operator's most recent still-usable conversation, or null.
 *
 * Used only when this tab has NO stored id — a browser restart, or a first
 * visit in a new tab. Recovering by lookup rather than moving
 * {@link CONVERSATION_STORAGE_KEY} to localStorage keeps the storage semantics
 * the comment there describes (an investigation belongs to its tab) and costs
 * one request.
 *
 * Reads a PAGE and picks the newest itself rather than asking for one row: the
 * descriptor endpoint's sort is a per-filter setting on the backend, not a
 * documented newest-first contract, and "resume my last conversation" silently
 * resuming the OLDEST one is the kind of bug nobody reports precisely.
 *
 * Only `READY` and `AWAITING_HUMAN` are restored, reusing the rule
 * {@link RESUMABLE_STATES} already states for the main chat: `ENDED` and
 * `ERROR` are terminal and `IN_PROGRESS` means a turn is still executing, so
 * dropping into any of them is worse than a clean start. An earlier version
 * excluded only `ENDED` and therefore restored `ERROR` conversations into a live
 * composer — the very "composer 4xx's on the next message" failure the exclusion
 * exists to prevent, reached through a state it had forgotten to list.
 */
async function findLatestOperatorConversation(agentId: string): Promise<string | null> {
  // Paged, not just page 0. This function explicitly refuses to trust the
  // endpoint's ordering — and page-0-only quietly depends on exactly that
  // ordering being newest-first, because with an oldest-first sort the real
  // newest conversation sits on the LAST page. Reading a bounded number of
  // pages and taking the maximum across all of them is the version that
  // actually holds under either sort.
  const candidates = [];
  for (let page = 0; page < RECOVERY_MAX_PAGES; page++) {
    const descriptors = await getConversationDescriptors(RECOVERY_PAGE_SIZE, page, "", agentId);
    candidates.push(
      ...descriptors.filter(
        (d) => RESUMABLE_STATES.has(d.conversationState) && !isOperatorProbeConversation(d),
      ),
    );
    // A short page is the last page. Stopping here is what keeps the common
    // case (a handful of conversations) at one request.
    if (descriptors.length < RECOVERY_PAGE_SIZE) break;
  }
  if (candidates.length === 0) return null;
  const newest = candidates.reduce((best, candidate) =>
    conversationRecency(candidate) > conversationRecency(best) ? candidate : best,
  );
  return parseConversationUri(newest.resource) || null;
}

/**
 * Whether a conversation was started by activation's own probes rather than by
 * the admin.
 *
 * The read canary and the write probe both run against the operator's OWN
 * agent, and `runPostActivationProbes` is deliberately fire-and-forget after
 * activation returns — so for ~30 seconds the newest conversation for that
 * agent is a machine one that is about to be ended. A tab opened in that window
 * used to restore it, showing "List the agents on this platform. Use your
 * tools; do not guess." as the admin's own transcript and then sitting on a
 * dead conversation. Also keeps them out of the History list, where a few
 * reconfigures and "Check again" clicks could otherwise evict real
 * investigations from a capped page.
 */
export function isOperatorProbeConversation(descriptor: { userId?: string }): boolean {
  return descriptor.userId === OPERATOR_PROBE_USER_ID;
}

/**
 * How recent a conversation is, for "restore my last one".
 *
 * One definition, shared with the History list's own ordering — two copies of
 * this expression let the conversation the tab restores and the row the list
 * shows first disagree about which is newest, which is exactly the silent
 * mismatch the sorting exists to prevent.
 */
export function conversationRecency(descriptor: {
  lastModifiedOn?: number;
  createdOn?: number;
}): number {
  return descriptor.lastModifiedOn ?? descriptor.createdOn ?? 0;
}

/**
 * The state a loaded conversation resolves to.
 *
 * `pausedPlaceholderId` anchors ONLY on a trailing agent bubble — the last
 * message must itself be the agent's. The backend writes its pending-approval
 * message into the paused step's output exactly like an ordinary answer, so when
 * the paused turn produced output that bubble IS the ask and `resolveApproval`
 * inserts the decision after it.
 *
 * When the paused turn produced NO output the trailing message is the user's
 * request, and there is no ask bubble to anchor on. `null` is correct there:
 * `resolveApproval` appends instead, which is the same shape the 409 pause path
 * already uses. Searching backwards for the last agent bubble — as this used to
 * — found the PREVIOUS turn's answer and spliced the decision and the result
 * above the request that asked for them.
 */
function hydratedState(conversationId: string, snapshot: SimpleConversationMemorySnapshot) {
  const messages = snapshotToMessages(snapshot);
  const paused = snapshot.conversationState === "AWAITING_HUMAN";
  const last = messages[messages.length - 1];
  return {
    conversationId,
    messages,
    isPaused: paused,
    // Always null from a simple snapshot — the wire never carries a reason
    // (see the note in conversations.ts). The rendered reason comes from
    // useApprovalStatus, which both surfaces already overlay.
    pauseReason: null,
    decidedPausedAt: paused ? (snapshot.hitlPausedAt ?? null) : null,
    pausedPlaceholderId: paused && last?.role === "agent" ? last.id : null,
    // Whatever the state of the conversation is, its transcript is worth
    // showing — the admin asked for THIS one, or it is the one their tab was
    // using. What must not survive is the ability to type into it: `ENDED` and
    // `ERROR` are terminal and `IN_PROGRESS` has a turn still executing, so the
    // next send would fail. The composer is closed instead of the transcript
    // being withheld.
    isReadOnly: !RESUMABLE_STATES.has(snapshot.conversationState),
    conversationState: snapshot.conversationState,
    // `resolveError` records that a human's decision failed to land, so it is
    // kept while the restored snapshot is STILL awaiting that decision. Once the
    // backend reports the conversation as no longer paused the decision did
    // land — keeping the banner then would leave a completed approval looking
    // permanently failed.
    ...(paused ? {} : { resolveError: null }),
    // A restored conversation has no live turn and no trace to show for one.
    events: [],
    liveToolCalls: [],
    liveToolsSettled: false,
  };
}

export const useOperatorChatStore = create<OperatorChatStore>((set, get) => ({
  messages: [],
  events: [],
  liveToolCalls: [],
  liveToolsSettled: false,
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
  isHydrating: false,
  isReadOnly: false,
  conversationState: null,
  abortController: null,
  resolveAbortController: null,
  decidedPausedAt: null,
  hydrateAbortController: null,

  clearError: () => set({ error: null }),

  reset: () => {
    get().abortController?.abort();
    get().resolveAbortController?.abort();
    // A restore in flight must not land in the clean slate the user just asked
    // for — the same reason the other two are aborted here.
    get().hydrateAbortController?.abort();
    // Discard any in-flight lazy create — its resolution must not resurrect a
    // conversation into the clean slate (see ensureConversation).
    creatingConversation = null;
    storeConversationId(null);
    // The admin asked for a clean slate; recovery must not undo that on the next
    // mount. See RECOVERY_DECLINED_STORAGE_KEY.
    storeRecoveryDeclined(true);
    // Free the sent bubbles' attachment preview URLs before dropping them —
    // takeForSend keeps them alive for the bubble thumbnails, so without this
    // every sent image leaks its blob (pinning the File) until page unload.
    revokeMessagePreviews(get().messages);
    set({
      messages: [],
      events: [],
      liveToolCalls: [],
      liveToolsSettled: false,
      tracesByMessageId: {},
      isStreaming: false,
      error: null,
      conversationId: null,
      isPaused: false,
      pauseReason: null,
      isResolvingPause: false,
      resolveError: null,
      pausedPlaceholderId: null,
      isHydrating: false,
      isReadOnly: false,
      conversationState: null,
      abortController: null,
      resolveAbortController: null,
      decidedPausedAt: null,
      hydrateAbortController: null,
    });
  },

  stop: () => {
    get().abortController?.abort();
    set({ abortController: null, isStreaming: false });
  },

  /**
   * Restore the tab's conversation on mount.
   *
   * Every mounted surface calls this on mount, and there can be two at once
   * (the full page and the docked drawer), so it declines in four separate
   * ways rather than trusting the caller:
   *
   * - a hydrate is already in flight (`hydrateAbortController`) — the second
   *   caller must not double-append the same transcript;
   * - `messages` is non-empty — there is a live transcript to preserve, and
   *   overwriting it with the stored one would drop this tab's decision entries;
   * - a turn is streaming — the same, mid-flight;
   * - nothing is configured, or nothing is stored and nothing recoverable.
   *
   * The 404 case is not an error. A conversation can be purged
   * (`purgeEndedConversations`) or deleted with the operator it belonged to, and
   * a stored id pointing at nothing should leave the admin with a working empty
   * chat, not a red banner about a conversation they had forgotten.
   */
  hydrate: async (config) => {
    if (!config?.agentId) return;
    const current = get();
    if (current.hydrateAbortController || hasLiveTranscript(current)) return;
    // A decision is being resolved. `resolveApproval` deliberately does not set
    // `isStreaming`, and the 409 pause path deliberately empties `messages`, so
    // during its 90-second poll every OTHER guard here is false — and a hydrate
    // that lands in that window reads the resumed answer, writes it, and then
    // has it appended a SECOND time when the poll settles.
    if (current.isResolvingPause) return;

    const storedId = current.conversationId;
    // "Nothing stored" means two different things: a fresh tab (restore the
    // newest) and "the admin just cleared the chat" (restore nothing). Only the
    // tombstone can tell them apart.
    if (!storedId && readRecoveryDeclined()) return;

    const controller = new AbortController();
    set({ hydrateAbortController: controller, isHydrating: true });
    try {
      const conversationId = storedId ?? (await findLatestOperatorConversation(config.agentId));
      if (controller.signal.aborted || !conversationId) return;

      let snapshot: SimpleConversationMemorySnapshot;
      try {
        // returnCurrentStepOnly MUST be false: the whole point is the whole
        // transcript, and in current-step-only mode the backend collapses
        // conversationOutputs to one element (see snapshotToMessages).
        snapshot = await getSimpleConversationLog(conversationId, false, false);
      } catch (error) {
        if (isApiError(error) && error.status === 404) {
          if (!controller.signal.aborted) {
            // Clear the PAUSE with the id. Leaving `isPaused` up while
            // `conversationId` is null renders an approval card whose Approve
            // hits `resolveApproval`'s `if (!conversationId) return` — no
            // request, no spinner, no error, permanently.
            storeConversationId(null);
            set({
              conversationId: null,
              isPaused: false,
              pauseReason: null,
              pausedPlaceholderId: null,
              decidedPausedAt: null,
            });
          }
          return;
        }
        throw error;
      }
      if (controller.signal.aborted) return;
      // Re-checked AFTER the reads, not just before them: a send() started while
      // this was in flight owns the screen, and its optimistic bubbles must not
      // be replaced by a transcript that predates them.
      const afterRead = get();
      if (hasLiveTranscript(afterRead) || afterRead.isResolvingPause) return;
      // ...and an attachment drop can claim a conversation without producing any
      // message at all: `ensureConversation` creates one and uploads INTO it, so
      // overwriting the id here would orphan the file in a conversation nothing
      // references — the exact hazard that function's in-flight dedupe exists to
      // prevent. It does not participate in that protocol, so it defers instead.
      if (afterRead.conversationId !== storedId || creatingConversation) return;

      storeConversationId(conversationId);
      set((s) => ({ ...s, ...hydratedState(conversationId, snapshot) }));
    } catch (error) {
      // A recovery lookup the admin never asked for must fail as quietly as the
      // 404 above: a speculative read planting a red banner over an empty chat
      // is worse than simply not restoring anything. A read of a STORED id is
      // different — that conversation is one the tab was demonstrably using.
      if (!controller.signal.aborted && storedId) set({ error: getErrorMessage(error) });
    } finally {
      if (get().hydrateAbortController === controller) {
        set({ hydrateAbortController: null, isHydrating: false });
      }
    }
  },

  /**
   * Load a conversation the admin picked out of the History tab.
   *
   * Unlike {@link hydrate} this REPLACES what is on screen — an explicit pick
   * is an instruction, not an offer — so it goes through `reset()` first. That
   * also aborts any in-flight turn, hydrate or pause resolution, and frees the
   * current bubbles' attachment previews, which a bare `set()` here would leak.
   */
  selectConversation: async (conversationId) => {
    // Read FIRST, touch the store second — the rule `use-chat.ts` records for
    // the same operation: "Clearing up front meant a failed read wiped the
    // conversation the user was looking at and left a blank pane with nothing to
    // go back to." An earlier version reset() before reading, so one 500 on a
    // stale row cost the admin the investigation they were in the middle of.
    const controller = new AbortController();
    set({ hydrateAbortController: controller, isHydrating: true });
    try {
      const snapshot = await getSimpleConversationLog(conversationId, false, false);
      // A reset() or a newer pick during the read wins — this one is stale.
      if (controller.signal.aborted || get().hydrateAbortController !== controller) return;
      // A turn the admin started while this loaded owns the screen. The same
      // check hydrate makes, and for the same reason: replacing `messages`
      // wholesale would drop the live user bubble and the streaming placeholder,
      // so every subsequent token would be written to a bubble that no longer
      // exists and the answer would vanish.
      if (get().isStreaming) return;

      // Now that the pick is known-good, swap: reset() aborts the in-flight
      // resolve, frees the outgoing bubbles' attachment previews, and clears the
      // traces — none of which may happen before we know there is something to
      // swap TO. It also sets the recovery tombstone, which this call then
      // clears, because adopting a conversation is the opposite of declining one.
      get().reset();
      storeConversationId(conversationId);
      storeRecoveryDeclined(false);
      set((s) => ({ ...s, ...hydratedState(conversationId, snapshot), hydrateAbortController: controller, isHydrating: true }));
    } catch (error) {
      if (controller.signal.aborted || get().hydrateAbortController !== controller) return;
      // Reported, unlike hydrate's silent 404: the admin clicked a row the list
      // said existed. Nothing else is touched — whatever was on screen stays,
      // because a failed pick is a failed navigation, not a reason to lose the
      // conversation they already had.
      set({ error: getErrorMessage(error) });
    } finally {
      if (get().hydrateAbortController === controller) {
        set({ hydrateAbortController: null, isHydrating: false });
      }
    }
  },

  ensureConversation: async (config) => {
    const existing = get().conversationId;
    if (existing) return existing;
    if (!config?.agentId) throw new Error("Operator is not configured");
    // In-flight dedupe: two attach gestures before the first create resolves
    // (drop then paste, or page + drawer) must share ONE conversation — a
    // second create would re-key the staging area mid-upload and orphan the
    // first gesture's file in a conversation nothing references.
    const inFlight = creatingConversation;
    if (inFlight) return inFlight;
    const agentId = config.agentId;
    // Nullable `let` + closure read instead of referencing a const inside its
    // own initializer: the comparisons only run after the first await, by
    // which point the assignment below has long happened.
    let creating: Promise<string> | null = null;
    creating = (async () => {
      try {
        const conversationId = await startConversation(operatorEnvironment(config), agentId);
        // A reset() while the create was in flight cleared the slot — the user
        // asked for a clean slate, so do not resurrect this conversation into
        // the store (callers still get the id; their uploads just target a
        // conversation the UI no longer tracks, exactly like any other
        // discarded staging).
        if (creatingConversation === creating) {
          storeConversationId(conversationId);
          // Adopting a conversation is the opposite of declining one: the admin
          // is working again, so a later restore of THIS id is wanted.
          storeRecoveryDeclined(false);
          set({ conversationId });
        }
        return conversationId;
      } finally {
        if (creatingConversation === creating) creatingConversation = null;
      }
    })();
    creatingConversation = creating;
    return creating;
  },

  send: async (config, input, context, attachments) => {
    // An attachment-only turn is legitimate (matching the main chat panel) —
    // block only when there is neither text nor a file.
    if (!config?.agentId || (!input.trim() && !attachments?.length)) {
      // The caller already drained its staging area — free the previews so a
      // refused turn does not leak them (the files stay stored server-side).
      if (attachments?.length) {
        attachments.forEach((a) => {
          if (a.previewUrl?.startsWith("blob:")) URL.revokeObjectURL(a.previewUrl);
        });
      }
      return;
    }
    // `set` below applies synchronously (unlike React's setState), so this
    // also closes a race a second mounted surface (the drawer, alongside the
    // full page) could otherwise trigger: a second send() invoked before this
    // one yields at its first `await` sees isStreaming already true here and
    // bails, before either has touched the network.
    if (get().isStreaming) {
      // Same refusal cleanup as above: the caller's staging area is already
      // drained, so the previews would otherwise leak.
      attachments?.forEach((a) => {
        if (a.previewUrl?.startsWith("blob:")) URL.revokeObjectURL(a.previewUrl);
      });
      return;
    }

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
      liveToolCalls: [],
      liveToolsSettled: false,
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
        operatorEnvironment(config),
        config.agentId,
        conversationId,
        Object.keys(turnContext).length ? { input, context: turnContext } : { input },
        controller.signal,
      );

      for await (const event of stream) {
        if (event.type === "token") {
          set((s) => ({
            ...s,
            // Output resuming IS the tool-finished signal — there is no live
            // per-call completion event, so without this the newest tool spun
            // forever under an answer that had already arrived.
            liveToolsSettled: s.liveToolCalls.length > 0 ? true : s.liveToolsSettled,
            messages: s.messages.map((m) =>
              m.id === agentId ? { ...m, content: m.content + event.data } : m,
            ),
          }));
          continue;
        }
        if (event.type === "error") {
          // The payload is JSON — `{"message":…,"code":…}` for the conditions
          // the backend rejects by design, `{"message":"Internal server
          // error","correlationId":…}` for genuine failures. Render the
          // message; the raw JSON blob is never a thing to show an admin.
          let errorText = event.data || "Stream error";
          let errorCode: string | null = null;
          try {
            const parsed: { message?: unknown; code?: unknown } = JSON.parse(event.data);
            if (typeof parsed.message === "string" && parsed.message) {
              errorText = parsed.message;
            }
            if (typeof parsed.code === "string") {
              errorCode = parsed.code;
            }
          } catch {
            // Not JSON — keep the raw text.
          }
          if (errorCode === "awaiting_approval") {
            // The send raced an undecided pause: the backend refused it WITHOUT
            // consuming it, over the already-open stream. Same recovery as the
            // 409 catch below — this is the same condition arriving on the
            // other channel (the stream opens before ConversationService
            // re-checks the state, so the rejection arrives as an event, not a
            // status). Show the pause, not an error: the input "failing" is
            // the approval gate working.
            revokeMessagePreviews([userMessage]);
            set((s) => ({
              ...s,
              isPaused: true,
              error: null,
              resolveError: null,
              messages: s.messages.filter((m) => m.id !== userMessage.id && m.id !== agentId),
            }));
            if (conversationId) {
              try {
                const snapshot = await getSimpleConversationLog(conversationId, false, true);
                const parts = extractOutputParts(
                  (snapshot.conversationOutputs ?? [])[
                    (snapshot.conversationOutputs?.length ?? 0) - 1
                  ],
                );
                const pendingText = parts.join("\n\n");
                const askId = nextId("agent");
                set((s) => {
                  const last = s.messages[s.messages.length - 1];
                  // Back-fill the ask so the banner has a request to point at
                  // — unless it is already on screen (the normal case: the
                  // pause rendered when it happened and this send raced it).
                  const askMissing =
                    pendingText.length > 0 &&
                    !(last?.role === "agent" && last.content === pendingText);
                  return {
                    ...s,
                    decidedPausedAt: snapshot.hitlPausedAt ?? null,
                    pauseReason: null,
                    ...(askMissing
                      ? {
                          messages: [
                            ...s.messages,
                            {
                              id: askId,
                              role: "agent" as const,
                              content: pendingText,
                              timestamp: Date.now(),
                            },
                          ],
                          pausedPlaceholderId: askId,
                        }
                      : {}),
                  };
                });
              } catch {
                // Best effort — the pause itself is already surfaced, and an
                // ask we could not read back is strictly less bad than a raw
                // error over a live approval.
              }
            }
            continue;
          }
          set((s) => ({ ...s, error: errorText }));
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
                hitlPausedAt?: string;
                conversationOutputs?: Record<string, unknown>[];
              } = JSON.parse(event.data);
              const outputs = snapshot.conversationOutputs ?? [];
              const lastOutput = outputs[outputs.length - 1];
              const parts = lastOutput ? extractOutputParts(lastOutput) : [];
              finalText = parts.join("\n\n");
              if (snapshot.conversationState === "AWAITING_HUMAN") {
                const pendingText = finalText;
                // Minted outside the updater, used only on the branch that
                // needs it — see the nextId note in resolveApproval.
                const askId = nextId("agent");
                set((s) => {
                  const bubble = s.messages.find((m) => m.id === agentId);
                  // A turn that streamed interim commentary ("The config
                  // checks out — I'll now start a test conversation, which
                  // needs your approval") KEEPS it, with the pending ask
                  // appended as its own bubble. Snapping the bubble to the
                  // pending text — as this used to — destroyed the one message
                  // that explained what the approval was about, the moment the
                  // approval card appeared. The stored transcript keeps only
                  // the pending text, so a reload shows just the ask — the
                  // commentary is this tab's live record, same as the decision
                  // entries.
                  const keepCommentary =
                    !!bubble?.content && !!pendingText && bubble.content !== pendingText;
                  return {
                    ...s,
                    isPaused: true,
                    pauseReason: null,
                    resolveError: null,
                    decidedPausedAt: snapshot.hitlPausedAt ?? null,
                    // The ask bubble is what resolveApproval anchors on
                    // (decision + answer are inserted after it): the appended
                    // ask when the commentary was kept, else this turn's own
                    // bubble — recorded by id whether or not it got any text.
                    pausedPlaceholderId: keepCommentary ? askId : agentId,
                    // A turn that streamed nothing still shows WHY it paused
                    // instead of an empty bubble.
                    messages: keepCommentary
                      ? [
                          ...s.messages,
                          {
                            id: askId,
                            role: "agent" as const,
                            content: pendingText,
                            timestamp: Date.now(),
                          },
                        ]
                      : pendingText
                        ? s.messages.map((m) =>
                            m.id === agentId && m.content !== pendingText
                              ? { ...m, content: pendingText }
                              : m,
                          )
                        : s.messages,
                  };
                });
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

        if (event.type === "tool_call") {
          // Live "Using {tool}…" signal — name only; arguments arrive later,
          // redacted, in the task_complete toolTrace.
          try {
            const parsed: { tool?: unknown } = JSON.parse(event.data);
            if (typeof parsed.tool === "string" && parsed.tool) {
              const tool = parsed.tool;
              set((s) => ({ ...s, liveToolCalls: [...s.liveToolCalls, tool], liveToolsSettled: false }));
            }
          } catch {
            // Malformed payload — the status line just keeps its last state.
          }
          continue;
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
        // The dropped optimistic bubble may carry attachment previews — free
        // them, the send never happened and no bubble will ever show them.
        revokeMessagePreviews([userMessage]);
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
        // The pause happened on a turn we never saw. The read below recovers
        // hitlPausedAt (the re-pause discriminator pollUntilSettled compares
        // against) — NOT a reason: the simple snapshot never carries one, and
        // the banner's reason overlays from approval-status on both surfaces.
        if (conversationId) {
          try {
            const snapshot = await getSimpleConversationLog(conversationId, false, true);
            set((s) => ({
              ...s,
              pauseReason: null,
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
   * it STAYS as the ask, with the decision entry and the answer inserted after
   * it; when we do not (a 409 pause, whose optimistic bubbles were dropped)
   * decision and answer are appended.
   */
  resolveApproval: async (verdict, note, toolDecisions) => {
    const conversationId = get().conversationId;
    if (!conversationId) return;

    const controller = new AbortController();
    set({ isResolvingPause: true, resolveError: null, resolveAbortController: controller });

    try {
      // Receipt baseline: which calls the turn had ALREADY executed before this
      // decision. Read BEFORE the resume — the conversation is still paused, so
      // the store is stable; read after it, the executing turn could race fresh
      // calls into the baseline and the receipt would under-report. Best-effort
      // by design: no baseline means no receipt, never a wrong one.
      //
      // The same read doubles as the pause-identity recovery: a pause detected
      // from a STREAMED turn has `decidedPausedAt: null`, because the SSE done
      // payload is a hand-built JSON that never carried `hitlPausedAt`. With
      // null, `pollUntilSettled`'s conservative fallback reads EVERY later
      // pause as "the one we decided" — so a resumed turn that paused again
      // spun the poll to its full timeout while the next batch sat undecided,
      // observed live as "the Approve button only works after a weird
      // timeout". The REST snapshot here is the same source the poll compares
      // against, so the identity is sound by construction.
      let preDecisionCalls: ReadonlySet<string> | null = null;
      let decidedPausedAt = get().decidedPausedAt;
      try {
        const baseline = await getSimpleConversationLog(conversationId, true, true);
        preDecisionCalls = new Set(executedCallsFromDetailed(baseline).keys());
        if (baseline.hitlPausedAt) {
          // Adopt unconditionally, not only when null: the conversation is
          // still paused at this moment, so this IS the pause being decided —
          // and taking it from the same REST endpoint the poll reads makes the
          // identity comparison string-vs-string from one serializer, immune
          // to any cross-channel format drift in whatever set it earlier.
          // Local only, NOT set into the store here: the store field keys the
          // approval-status cache, and rewriting it mid-decide would flip that
          // key and refetch the banner under the approver for nothing — the
          // settle path below writes the store value that actually matters.
          decidedPausedAt = baseline.hitlPausedAt;
        }
      } catch {
        preDecisionCalls = null;
        // The receipt is best-effort, but the pause IDENTITY is not: with
        // decidedPausedAt still null (a streamed pause on a backend whose done
        // events carry no hitlPausedAt), proceeding after a transient failure
        // here re-opens the very stall this read exists to close — the poll
        // reads the next pause as "the one we decided" until its timeout. One
        // lightweight retry recovers it; a persistent outage is left to the
        // resume/poll below, whose error handling already reports it with the
        // pause intact.
        if (decidedPausedAt === null) {
          try {
            const light = await getSimpleConversationLog(conversationId, false, true);
            decidedPausedAt = light.hitlPausedAt ?? null;
          } catch {
            // Both reads down: the resume below will surface the outage.
          }
        }
      }
      // The baseline read is a suspension point that did not exist when the
      // resume was the first await: a reset() or selectConversation() during
      // it aborts this controller, and submitting the decision anyway would
      // approve (or reject) a conversation the user has already discarded —
      // a real backend side effect, not just a stale render.
      if (controller.signal.aborted) return;
      // A history pick in flight wins over the submit. selectConversation
      // deliberately aborts this controller only AFTER its read succeeds (a
      // failed pick must not cost the conversation on screen), so during that
      // read the abort guard above passes — and the decision would land on the
      // conversation the admin is switching away from. hydrate() declines
      // outright while isResolvingPause is true, so a non-null controller here
      // can only be an explicit pick. isResolvingPause is cleared because the
      // pick's own reset() may never run if its read fails.
      if (get().hydrateAbortController !== null || get().conversationId !== conversationId) {
        set({ isResolvingPause: false });
        return;
      }
      await resumeConversation(conversationId, { verdict, note, toolDecisions });
      const snapshot = await pollUntilSettled(conversationId, controller.signal, decidedPausedAt);
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

      // A decision must ALWAYS leave a visible trace. Approving used to be
      // indistinguishable from nothing happening when the resumed turn produced
      // no text — it had paused again on the same tool, and the transcript said
      // nothing at all. The record goes in regardless of what came back.
      //
      // A top-level APPROVED can still carry per-call REJECTEDs — that is how the
      // banner submits "approve these, not that one" (a top-level REJECTED is
      // all-or-nothing and carries no map). Recording it as a flat "approved"
      // would put a claim in the permanent transcript the approver never made,
      // so the rejected calls are counted and named.
      const rejectedCalls = toolDecisions
        ? Object.values(toolDecisions).filter((d) => d.verdict === "REJECTED").length
        : 0;
      const decisionCode =
        verdict !== "APPROVED" ? "rejected" : rejectedCalls > 0 ? "partial" : "approved";
      const decisionEntry: ChatMessage = {
        id: nextId("agent"),
        role: "system",
        kind: "decision",
        code: decisionCode,
        ...(decisionCode === "partial" ? { count: rejectedCalls } : {}),
        content:
          decisionCode === "approved"
            ? "You approved this request."
            : decisionCode === "partial"
              ? `You approved this request — ${rejectedCalls} call(s) rejected.`
              : "You rejected this request.",
        timestamp: Date.now(),
      };
      // The receipt: what this decision actually caused to run. The model often
      // proceeds straight from an approved call into its next tool call with no
      // text in between, so without this the transcript read "You approved" →
      // next ask, and the created agent appeared nowhere — the approver's most
      // common question ("did it work?") had no answer on screen. Derived as a
      // diff against the pre-decision baseline so repeat pauses in the same
      // turn do not re-list earlier calls.
      let receipt: ChatMessage | null = null;
      if (preDecisionCalls) {
        try {
          const ran = [
            ...executedCallsFromDetailed(
              await getSimpleConversationLog(conversationId, true, true),
            ),
          ].filter(([name]) => !preDecisionCalls.has(name));
          if (ran.length > 0) {
            const callsText = ran
              .map(([name, ok]) => `${name} ${ok ? "✓" : "✕"}`)
              .join(", ");
            receipt = {
              id: nextId("agent"),
              role: "system",
              kind: "notice",
              code: "executed",
              detail: callsText,
              content: `Ran ${callsText}`,
              timestamp: Date.now(),
            };
          }
        } catch {
          // Best effort — the decision entry and the answer still land.
        }
      }

      // ...and when there is no answer to show, say WHY rather than leaving the
      // approver staring at an unchanged screen.
      const silentOutcome: ChatMessage | null = parts.length > 0
        ? null
        : {
            id: nextId("agent"),
            role: "system",
            kind: "notice",
            code: rePaused ? "rePaused" : "noReply",
            content: rePaused
              ? "The turn paused again and needs another decision."
              : "The turn finished without a reply.",
            timestamp: Date.now(),
          };

      set((s) => {
        const settled = {
          isPaused: rePaused,
          pauseReason: null,
          isResolvingPause: false,
          decidedPausedAt: rePaused ? (snapshot.hitlPausedAt ?? null) : null,
        };
        const trail = [
          decisionEntry,
          ...(receipt ? [receipt] : []),
          ...(silentOutcome ? [silentOutcome] : []),
        ];
        if (newBubbles.length === 0) {
          // The formerly silent path: the decision and its outcome are still
          // recorded, so the approver always sees that something happened.
          return {
            ...s,
            ...settled,
            messages: [...s.messages, ...trail],
            pausedPlaceholderId: null,
          };
        }
        // Read from `s`, never from an outer closure: `messages` here must be
        // this exact update's starting point, not whatever render happened to
        // trigger the call.
        const placeholderId = s.pausedPlaceholderId;
        const placeholderIdx = placeholderId
          ? s.messages.findIndex((m) => m.id === placeholderId)
          : -1;
        let messages: ChatMessage[];
        if (placeholderIdx >= 0) {
          // The ask bubble ("I need your approval to run X") STAYS, the decision
          // reads after it, and the answer follows as its own bubble — the flow
          // an approver expects: request → decision → result. The ask used to be
          // overwritten by the answer, which put the decision rule ABOVE the very
          // message it was answering — reading as approval of a request that had
          // not been made yet. The server drops its copy of the ask from the
          // resolved step, so a reload shows only the answer — like the decision
          // rules themselves, the fuller sequence is this tab's record of what
          // happened, not a claim about the stored transcript.
          //
          // The placeholder keeps its id, so the paused turn's pipeline trace
          // (keyed by it in tracesByMessageId) stays attached to the ask.
          messages = [
            ...s.messages.slice(0, placeholderIdx),
            { ...s.messages[placeholderIdx]!, isStreaming: false },
            // `trail` here is [decision, receipt?] — silentOutcome is only
            // built when there are no answer bubbles, and this branch has them.
            ...trail,
            ...newBubbles,
            ...s.messages.slice(placeholderIdx + 1),
          ];
        } else {
          messages = [...s.messages, ...trail, ...newBubbles];
        }
        // The LAST bubble rendered: on a re-pause it holds the NEW pending
        // message and becomes the ask the next decision reads after; a
        // multi-part output must anchor there, not on its opening part.
        const renderedId = newBubbles[newBubbles.length - 1]!.id;
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
  const liveToolCalls = useOperatorChatStore((s) => s.liveToolCalls);
  const liveToolsSettled = useOperatorChatStore((s) => s.liveToolsSettled);
  const tracesByMessageId = useOperatorChatStore((s) => s.tracesByMessageId);
  const isStreaming = useOperatorChatStore((s) => s.isStreaming);
  const error = useOperatorChatStore((s) => s.error);
  const conversationId = useOperatorChatStore((s) => s.conversationId);
  const isPaused = useOperatorChatStore((s) => s.isPaused);
  const pauseReason = useOperatorChatStore((s) => s.pauseReason);
  const isResolvingPause = useOperatorChatStore((s) => s.isResolvingPause);
  const resolveError = useOperatorChatStore((s) => s.resolveError);
  const decidedPausedAt = useOperatorChatStore((s) => s.decidedPausedAt);
  const pausedPlaceholderId = useOperatorChatStore((s) => s.pausedPlaceholderId);
  const isHydrating = useOperatorChatStore((s) => s.isHydrating);
  const isReadOnly = useOperatorChatStore((s) => s.isReadOnly);
  const conversationState = useOperatorChatStore((s) => s.conversationState);

  const rawSend = useOperatorChatStore((s) => s.send);
  const rawEnsureConversation = useOperatorChatStore((s) => s.ensureConversation);
  const rawHydrate = useOperatorChatStore((s) => s.hydrate);
  const stop = useOperatorChatStore((s) => s.stop);
  const reset = useOperatorChatStore((s) => s.reset);
  const resolveApproval = useOperatorChatStore((s) => s.resolveApproval);
  const clearError = useOperatorChatStore((s) => s.clearError);
  const selectConversation = useOperatorChatStore((s) => s.selectConversation);

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
  const hydrate = useCallback(() => rawHydrate(config), [rawHydrate, config]);

  return {
    messages,
    events,
    liveToolCalls,
    liveToolsSettled,
    tracesByMessageId,
    isStreaming,
    error,
    conversationId,
    isPaused,
    pauseReason,
    isResolvingPause,
    resolveError,
    decidedPausedAt,
    pausedPlaceholderId,
    isHydrating,
    isReadOnly,
    conversationState,
    send,
    ensureConversation,
    hydrate,
    stop,
    reset,
    resolveApproval,
    clearError,
    selectConversation,
  };
}
