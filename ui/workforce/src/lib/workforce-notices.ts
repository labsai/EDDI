import type { UserEvent } from "@eddi/core/user-events";
import { chatHref } from "@/lib/workforce-sessions";
import { teamSessionHref } from "@/lib/workforce-approvals";

/**
 * A notice Workforce shows for a user event (T2.3, T2.8): which sentence, how
 * loud, and where its link goes. Events carry ids and enum states only, so
 * the sentence is ours and generic — never the conversation's text.
 */
export interface Notice {
  /** i18n key under `Workforce.notices`. */
  key: string;
  tone: "info" | "error";
  /** Where "Open" goes, or null when the event names nothing Workforce opens. */
  href: string | null;
  /** The conversation or team session it is about, to skip the one on screen. */
  conversationId: string | null;
}

function regularHref(event: UserEvent): string | null {
  return event.agentId && event.conversationId ? chatHref(event.agentId, event.conversationId) : null;
}

function teamHref(event: UserEvent): string | null {
  return event.groupId && event.groupConversationId ? teamSessionHref(event.groupId, event.groupConversationId) : null;
}

function jobFinishedKey(state: string | undefined): string {
  switch (state) {
    case undefined:
    case "done":
      return "jobFinished";
    case "limit":
      return "jobStoppedLimit";
    case "cost_ceiling":
      return "jobStoppedCost";
    default:
      return "jobStopped";
  }
}

function teamEndedKey(state: string | undefined): string {
  switch (state) {
    case "FAILED":
      return "teamFailed";
    case "REJECTED":
    case "CANCELLED":
      return "teamStopped";
    default:
      return "teamEnded";
  }
}

function decidedKey(state: string | undefined): string {
  switch (state) {
    case "APPROVED":
      return "approvalApproved";
    case "REJECTED":
      return "approvalRejected";
    case "MIXED":
      return "approvalMixed";
    case "ABORTED":
    case "CANCELLED":
      return "approvalStopped";
    default:
      return "approvalDecided";
  }
}

/**
 * The notice for `event`, or null when it deserves none:
 *
 * - `job.*` — a long job finished, stopped or failed, a scheduled run completed (T2.8).
 *   Whether a recurring run's notice is shown is {@link NoticeGate}'s call.
 * - `team.ended`, and `team.waiting_for_human` only on the copy whose `yourTurn`
 *   is true — the owner's copy of someone else's turn is not news for them.
 * - `approval.decided` only for a pause that was the person's own (`ownsApproval`):
 *   "your paused conversation continues" (T2.3). An approver's inbox changing
 *   is not worth a toast; the indicator's count already says it.
 * - `approval.pending`, `team.started`, `notification.created` — none: the
 *   indicator counts the first, the second is something the person just did,
 *   and the third is the Manager's bell.
 */
export function noticeFor(event: UserEvent, ownsApproval: boolean): Notice | null {
  switch (event.kind) {
    case "job.finished":
      return { key: jobFinishedKey(event.state), tone: "info", href: regularHref(event), conversationId: event.conversationId ?? null };
    case "job.run_completed":
      return { key: "scheduleCompleted", tone: "info", href: regularHref(event), conversationId: event.conversationId ?? null };
    case "job.failed":
      return {
        key: event.jobKind === "schedule" ? "scheduleFailed" : "jobFailed",
        tone: "error",
        href: regularHref(event),
        conversationId: event.conversationId ?? null,
      };
    case "team.ended":
      return {
        key: teamEndedKey(event.state),
        tone: event.state === "FAILED" ? "error" : "info",
        href: teamHref(event),
        conversationId: event.groupConversationId ?? null,
      };
    case "team.waiting_for_human":
      return event.yourTurn === true
        ? { key: "teamYourTurn", tone: "info", href: teamHref(event), conversationId: event.groupConversationId ?? null }
        : null;
    case "approval.decided": {
      if (!ownsApproval) return null;
      const group = event.surface === "group";
      return {
        key: decidedKey(event.state),
        tone: "info",
        href: group ? teamHref(event) : regularHref(event),
        conversationId: (group ? event.groupConversationId : event.conversationId) ?? null,
      };
    }
    default:
      return null;
  }
}

/**
 * Whether `location` already shows the conversation or team session a notice
 * is about. That page updates itself; a toast on top of it would only repeat
 * what the person is looking at.
 */
export function isShowing(location: { pathname: string; search: string }, conversationId: string | null): boolean {
  if (!conversationId) return false;
  const params = new URLSearchParams(location.search);
  return params.get("conversationId") === conversationId || params.get("conversation") === conversationId;
}

/**
 * An event older than this is not announced: it was delivered late (a slow
 * reconnect, a backlog) and the page shows its outcome by now. Compared with
 * the server's `ts`, so it also absorbs modest clock skew between the two.
 */
export const NOTICE_MAX_AGE_MS = 2 * 60_000;

/** Schedules remembered as failing; the oldest is forgotten past this. */
const MAX_TRACKED_SCHEDULES = 500;

/**
 * Decides whether a notice `noticeFor` picked is actually shown, so a
 * recurring job does not toast (and notify the OS) on every fire:
 *
 * - A scheduled run that completed is shown only when it ends a failure
 *   streak of that schedule — a 60 s heartbeat would otherwise toast every
 *   minute. The run's own page and history say it ran.
 * - A failed run is shown once per schedule until that schedule succeeds
 *   again: a failed fire is retried, and each attempt raises `job.failed`.
 * - Anything older than {@link NOTICE_MAX_AGE_MS} is not shown.
 *
 * One instance per tab (the layout mounts the notices once); kept in memory
 * only, so a reload starts fresh.
 */
export class NoticeGate {
  /** Schedule (or, without one, conversation) ids whose failure was shown. */
  private readonly failing = new Set<string>();

  admit(event: UserEvent, now: number): boolean {
    if (typeof event.ts === "number" && event.ts > 0 && now - event.ts > NOTICE_MAX_AGE_MS) return false;
    const key = event.scheduleId ?? event.conversationId ?? null;
    switch (event.kind) {
      case "job.run_completed":
        // A recovery is news; a routine success is not.
        return key !== null && this.failing.delete(key);
      case "job.failed":
        if (key === null) return true;
        if (this.failing.has(key)) return false;
        this.failing.add(key);
        if (this.failing.size > MAX_TRACKED_SCHEDULES) {
          const oldest = this.failing.values().next().value;
          if (oldest !== undefined) this.failing.delete(oldest);
        }
        return true;
      default:
        return true;
    }
  }
}

/** localStorage key of the opt-in to browser notifications. */
export const BROWSER_NOTICES_KEY = "eddi-workforce-browser-notices";
