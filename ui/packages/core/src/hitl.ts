/* ──────────────────────────────────────────────
   @eddi/core — Human-in-the-Loop status
   A paused conversation has no push channel on the 1:1 REST/SSE surface, so a
   client polls approval-status to learn that the pause resolved — including
   when it auto-resolves by timeout policy.

   Derived from the Chat UI's `api/hitl-api.ts` (status, cancel, deadline maths,
   poll cadence) and the Manager's `lib/api/hitl.ts` (resume). Which client may
   SUBMIT a decision is the client's policy, not this package's: the Chat UI
   never does — an end user approving their own gate defeats the oversight the
   pause exists to provide.
   ────────────────────────────────────────────── */

import { defaultHttp, encodeSegment, type HttpClient } from "./http";
import type { ConversationState } from "./types";

export interface PendingToolCall {
  callId: string;
  toolName: string;
  source: string;
  /** Redacted and length-capped — the raw arguments are never exposed. */
  arguments: string;
  argsTruncated: boolean;
  gateReason: string;
}

export interface ToolCallPauseDetails {
  type: "TOOL_CALL";
  calls: PendingToolCall[];
  executedUngatedCalls: string[];
  outcomeUnknown: string[];
}

export interface RulePauseDetails {
  type: "RULE";
  reason: string | null;
  actions: string[];
}

export type PauseDetails = ToolCallPauseDetails | RulePauseDetails;

/** The `detail=summary` projection of GET /agents/{id}/approval-status. */
export interface ApprovalStatus {
  conversationId: string;
  state: ConversationState;
  /** ISO-8601 instant, or "" when not paused. */
  pausedAt: string;
  pauseReason: string;
  /** AUTO_APPROVE | AUTO_REJECT | ABORT | WAIT_INDEFINITELY, or "". */
  timeoutPolicy: string;
  /** ISO-8601 duration, e.g. "PT15M", or "". */
  approvalTimeout: string;
  pauseDetails: PauseDetails | null;
}

export type HitlVerdict = "APPROVED" | "REJECTED";

/**
 * Per-tool-call verdict inside a TOOL_CALL resume body. Calls not listed in
 * `HitlDecision.toolDecisions` inherit the top-level verdict.
 * `amendedArguments` is only valid on an APPROVED call, must be a JSON-object
 * string, and cannot amend a call whose arguments were truncated at pause time.
 */
export interface ToolCallDecision {
  verdict: HitlVerdict;
  note?: string;
  amendedArguments?: string;
}

/** A human decision on a paused conversation (POST /agents/{id}/resume). */
export interface HitlDecision {
  verdict: HitlVerdict;
  note?: string;
  /** Per-callId verdicts for a TOOL_CALL pause. Omit for RULE pauses. */
  toolDecisions?: Record<string, ToolCallDecision>;
  /**
   * The pause this decision was made for. A backend that knows the field
   * refuses the decision with 409 once the conversation has been resumed and
   * paused again; an older one ignores it.
   */
  pauseId?: string;
}

/**
 * The day component is captured, not merely tolerated.
 *
 * It used to be matched and discarded, so `P1DT1H` read as one hour instead of
 * 25 — the deadline shown to the user was a day early. The `T` section is also
 * optional: the backend parses `approvalTimeout` with `Duration.parse`, which
 * accepts a bare `P2D`, and that previously failed to match at all and
 * reported no deadline.
 */
const ISO_DURATION = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/;

/** Parse an ISO-8601 duration into milliseconds. */
export function parseIsoDuration(value: string): number | null {
  if (!value) return null;
  const m = ISO_DURATION.exec(value.trim());
  if (!m) return null;
  const [, d, h, min, s] = m;
  if (!d && !h && !min && !s) return null;
  return (Number(d ?? 0) * 86400 + Number(h ?? 0) * 3600 + Number(min ?? 0) * 60 + Number(s ?? 0)) * 1000;
}

/**
 * Epoch millis at which the pause auto-decides, or null when it never does.
 *
 * `pausedAt` is `Instant.toString()` on the summary endpoint (ISO-8601), but
 * the `detail=full` snapshot serializes instants as epoch millis, so both
 * forms are accepted.
 */
export function approvalDeadline(status: ApprovalStatus): number | null {
  const timeout = parseIsoDuration(status.approvalTimeout);
  if (timeout === null || !status.pausedAt) return null;

  const numeric = Number(status.pausedAt);
  const pausedAt = Number.isFinite(numeric) && status.pausedAt.trim() !== "" ? numeric : Date.parse(status.pausedAt);
  if (!Number.isFinite(pausedAt)) return null;

  return pausedAt + timeout;
}

/** Names of the tools awaiting approval; empty unless this is a TOOL_CALL pause. */
export function gatedToolNames(status: ApprovalStatus): string[] {
  const details = status.pauseDetails;
  if (!details || details.type !== "TOOL_CALL") return [];
  return (details.calls ?? []).map((c) => c.toolName).filter(Boolean);
}

/** The pause's reason as the backend recorded it: a RULE pause's own, else the summary's. */
export function pauseReasonOf(status: ApprovalStatus): string {
  const details = status.pauseDetails;
  return (details && details.type === "RULE" ? details.reason : null) || status.pauseReason;
}

/**
 * States in which the backend has finished with the turn.
 *
 * Deliberately EXCLUDES IN_PROGRESS: `resumeConversation` compare-and-sets
 * AWAITING_HUMAN → IN_PROGRESS *before* executing the resumed turn, and only
 * persists the final state on completion. A client that treats "anything but
 * AWAITING_HUMAN" as resolved therefore stops watching while the approved
 * answer is still being generated, and never sees it.
 */
const SETTLED_STATES: ReadonlyArray<ConversationState> = ["READY", "ENDED", "ERROR", "EXECUTION_INTERRUPTED"];

/** True once the backend has finished with the turn. */
export function isSettledState(state: ConversationState | null | undefined): boolean {
  return !!state && SETTLED_STATES.includes(state);
}

/**
 * How long a "settled" read is distrusted before the pause has ever been seen
 * stored. The stream's `done` announces a pause BEFORE the paused memory is
 * persisted, and approval-status reads the store, so the first read can say
 * READY for a conversation that is, in fact, about to be paused.
 */
export const UNOBSERVED_PAUSE_WINDOW_MS = 10_000;

/** Poll delays while the pause has not been seen stored yet; the last repeats. */
const UNOBSERVED_POLL_BACKOFF_MS = [500, 1_000, 2_000] as const;

/** Delay before the next poll while the pause has not been seen stored yet. */
export function nextUnobservedPollDelay(attempt: number): number {
  const n = Math.min(Math.max(0, attempt), UNOBSERVED_POLL_BACKOFF_MS.length - 1);
  return UNOBSERVED_POLL_BACKOFF_MS[n] ?? 2_000;
}

const POLL_BASE_MS = 3_000;
const POLL_MAX_MS = 30_000;

/**
 * Delay before the next approval-status poll, given how many polls have
 * already happened. Starts responsive so a quick approval feels immediate,
 * then backs off — approvals routinely take minutes, and a fixed fast interval
 * would hammer the endpoint for the whole wait. Capped so a resolved pause is
 * never missed for more than half a minute.
 */
export function nextPollDelay(attempt: number): number {
  const n = Math.max(0, attempt);
  return Math.min(POLL_BASE_MS * 2 ** Math.min(n, 10), POLL_MAX_MS);
}

/** The HITL endpoints of a 1:1 conversation over one {@link HttpClient}. */
export function createHitlApi(http: HttpClient) {
  /** Read the approval status of a conversation (summary view). */
  function getApprovalStatus(conversationId: string): Promise<ApprovalStatus | null> {
    return http.requestJson<ApprovalStatus>(
      `/agents/${encodeSegment(conversationId)}/approval-status?detail=summary`,
      undefined,
      "Failed to read approval status",
    );
  }

  /**
   * Cancel a running or paused turn: the way out of a pause
   * (AWAITING_HUMAN → EXECUTION_INTERRUPTED) and the stop-generating control.
   * Throws with status 409 when there is nothing to cancel.
   */
  async function cancelConversation(conversationId: string): Promise<void> {
    await http.request(`/agents/${encodeSegment(conversationId)}/cancel`, { method: "POST" }, "Failed to cancel");
  }

  /** Submit a human decision to resume a paused conversation. */
  async function resumeConversation(conversationId: string, decision: HitlDecision): Promise<void> {
    await http.request(
      `/agents/${encodeSegment(conversationId)}/resume`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(decision),
      },
      "Failed to resume",
    );
  }

  return { getApprovalStatus, cancelConversation, resumeConversation };
}

export type HitlApi = ReturnType<typeof createHitlApi>;

/** The HITL API over {@link defaultHttp}. */
export const { getApprovalStatus, cancelConversation, resumeConversation } = createHitlApi(defaultHttp);

// ── Group HITL: what a stored group discussion carries about its pause ──

export type HitlTimeoutPolicy =
  | "WAIT_INDEFINITELY"
  | "AUTO_APPROVE"
  | "AUTO_REJECT"
  | "ABORT";

export type HitlGranularity = "PHASE" | "TASK";

/** One block of the designer's `approvalContext`, as rendered when the pause was committed. */
export interface ApprovalContextItem {
  label: string;
  /** Plain text — conversation data, already redacted; never markup. */
  text: string;
  /** The text was cut at the block's `maxChars`. */
  truncated?: boolean;
  /** `agent`, `group`, `rule:<match>`, or another producer such as `guardrail`. */
  source?: string | null;
  /** The gated call a `rule:` block belongs to. */
  callId?: string | null;
  /** `render_failed` or `too_large` — the block could not be rendered; `text` is empty. */
  error?: string | null;
}

/**
 * `approvalContext` of `GET …/approval-status` (regular and group) and of the
 * group conversation itself: rendered once at pause time, re-redacted at serve
 * time. `null` when nothing is configured or the conversation is not paused.
 */
export interface ApprovalContextView {
  items: ApprovalContextItem[];
  /** Further blocks were dropped to stay within the total cap. */
  truncated?: boolean;
  /** SHA-256 of what was shown, for an audit trail. */
  digest?: string | null;
}
