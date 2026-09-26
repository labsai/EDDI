import { ApiClientError } from "@/lib/api-client";
import type { ApprovalStatusSummary } from "@/lib/api/hitl";

/**
 * Binding a human decision to the pause the human actually looked at.
 *
 * A resume carries a verdict, and the backend applies it to whatever the
 * conversation is paused on NOW. Every approval surface decides from a copy of
 * the pause that can be seconds or minutes old — the approvals inbox polls every
 * ten seconds — and in that window the pause can be resolved elsewhere and the
 * turn can pause again on something different. A plain `{verdict: "APPROVED"}`
 * then approves the new pause, and for a tool-call pause the top-level verdict
 * covers every gated call nobody reviewed: `requireExplicitPerCall` is bypassed
 * without anyone seeing a single call.
 *
 * Two layers, because the backend grew the proper one only recently:
 *
 * 1. `pauseId` (the pause start's epoch milliseconds, reported by
 *    `approval-status`). A backend that knows it refuses a decision for a pause
 *    that is no longer current with 409 — atomically, on the server. A backend
 *    that predates it ignores the unknown field.
 * 2. For a backend that reports no `pauseId`, the pause is re-read immediately
 *    before the resume and compared with what was shown. That leaves only the
 *    round trip open, instead of the whole time the decision took.
 */

/** What an approval surface showed the reviewer — enough to recognise the pause again. */
export interface ShownPause {
  /** `pauseId` from approval-status, when the backend reports one. */
  pauseId?: string | null;
  /** When the shown pause started, as the backend serialized it. */
  pausedAt?: string | null;
  /**
   * The kind of pause that was shown: `pauseDetails.type` from approval-status,
   * or the `pauseType` of a pending-approvals row. Null/absent means a
   * behaviour-rule pause, as it does on the backend.
   */
  pauseType?: string | null;
  /** For a tool-call pause, the call ids the reviewer saw. */
  callIds?: readonly string[] | null;
}

/** Thrown when the pause changed between being shown and being decided. */
export class PauseChangedError extends Error {
  constructor() {
    super("The pending approval changed since it was shown");
    this.name = "PauseChangedError";
  }
}

/** A non-blank pause id, or undefined. The backend sends `""` once a conversation is not paused. */
function usablePauseId(id: string | null | undefined): string | undefined {
  const trimmed = id?.trim();
  return trimmed ? trimmed : undefined;
}

/** RULE and a missing type are the same pause kind; everything else is itself. */
function pauseKind(type: string | null | undefined): string {
  return !type || type === "RULE" ? "RULE" : type;
}

/**
 * Whether two serializations of a pause start name the same instant.
 *
 * Compared as strings first, then as parsed milliseconds: the pending list and
 * approval-status serialize the same `Instant` by different routes, and one of
 * them may carry sub-millisecond digits the other lacks.
 */
function sameInstant(a: string, b: string): boolean {
  if (a === b) return true;
  const ta = Date.parse(a);
  const tb = Date.parse(b);
  return Number.isFinite(ta) && Number.isFinite(tb) && ta === tb;
}

/** A pause as read just now: whether the subject is paused at all, and which pause. */
export interface CurrentPause extends ShownPause {
  paused: boolean;
}

/** Summarise a conversation's approval-status response as the pause it shows. */
export function shownPauseOf(status: ApprovalStatusSummary | null | undefined): ShownPause {
  if (!status) return {};
  const details = status.pauseDetails;
  return {
    pauseId: usablePauseId(status.pauseId),
    pausedAt: status.pausedAt || null,
    pauseType: details?.type ?? null,
    callIds: details?.type === "TOOL_CALL" ? details.calls.map((c) => c.callId) : null,
  };
}

/** A conversation's approval-status, as the current pause. */
export function currentPauseOf(status: ApprovalStatusSummary): CurrentPause {
  return { ...shownPauseOf(status), paused: status.state === "AWAITING_HUMAN" };
}

/**
 * A group discussion's approval-status summary, as the current pause.
 *
 * The group summary is untyped (`Record<string, unknown>`) and names its own
 * states: a discussion waiting on a decision is `AWAITING_APPROVAL`, and its
 * `pauseType` is `PHASE`, `TASK` or `HUMAN_TURN` rather than RULE/TOOL_CALL.
 */
export function currentGroupPauseOf(summary: Record<string, unknown>): CurrentPause {
  const text = (key: string) => {
    const value = summary[key];
    return typeof value === "string" && value.trim() ? value : null;
  };
  return {
    paused: text("state") === "AWAITING_APPROVAL",
    pauseId: usablePauseId(text("pauseId")),
    pausedAt: text("pausedAt"),
    pauseType: text("pauseType"),
    callIds: null,
  };
}

/**
 * Whether `current` (read just now) is still the pause the reviewer was shown.
 *
 * Deliberately strict: anything that cannot be matched is a change. Asking the
 * reviewer to look again costs a click; approving the wrong pause cannot be
 * undone.
 */
export function isSamePause(shown: ShownPause, current: CurrentPause): boolean {
  if (!current.paused) return false;
  const currentId = usablePauseId(current.pauseId);
  const shownId = usablePauseId(shown.pauseId);
  if (shownId && currentId) return shownId === currentId;
  // Without an id, the start instant is what tells one pause from the next of
  // the same kind. A shown pause that carries neither cannot be recognised
  // again, so it is refused rather than matched on kind alone.
  if (!shownId && !shown.pausedAt) return false;
  if (pauseKind(shown.pauseType) !== pauseKind(current.pauseType)) return false;
  if (shown.pausedAt && current.pausedAt && !sameInstant(shown.pausedAt, current.pausedAt)) {
    return false;
  }
  if (shown.callIds && current.callIds) {
    const seen = new Set(shown.callIds);
    if (seen.size !== current.callIds.length) return false;
    if (!current.callIds.every((id) => seen.has(id))) return false;
  }
  return true;
}

/**
 * The decision to send for the pause that was shown, or a `PauseChangedError`.
 *
 * With a `pauseId` in hand the backend does the check, so nothing is read. Without
 * one the pause is re-read through `readCurrent` and compared; if the fresh read
 * carries a `pauseId`, it goes along so the backend closes the remaining gap.
 */
export async function bindDecisionToPause<D extends { pauseId?: string }>(
  decision: D,
  shown: ShownPause,
  readCurrent: () => Promise<CurrentPause>,
): Promise<D> {
  const shownId = usablePauseId(shown.pauseId);
  if (shownId) return { ...decision, pauseId: shownId };
  const current = await readCurrent();
  if (!isSamePause(shown, current)) throw new PauseChangedError();
  const currentId = usablePauseId(current.pauseId);
  return currentId ? { ...decision, pauseId: currentId } : decision;
}

/**
 * Whether a failed resume means "the pause changed", from either layer: our own
 * pre-check, or the backend's 409 for a `pauseId` that is no longer current.
 */
export function isPauseChanged(err: unknown): boolean {
  if (err instanceof PauseChangedError) return true;
  // The conversation resume answers a stale pauseId with "…changed since this
  // decision was made (pauseId no longer current)…". The group approve answers
  // EVERY wrong-state refusal — a pauseId mismatch included, since the
  // coordinator's mismatch is a GroupDiscussionException — with "Group
  // conversation is not awaiting approval — it may have been resolved…". For a
  // decision made from a displayed pause both mean the same thing: what the
  // reviewer saw is no longer what is pending. A conversation that is simply not
  // resumable ("not in a resumable state") stays an ordinary error.
  return (
    err instanceof ApiClientError &&
    err.status === 409 &&
    /changed since this decision|pauseId no longer current|group conversation is not awaiting approval/i.test(
      err.message,
    )
  );
}
