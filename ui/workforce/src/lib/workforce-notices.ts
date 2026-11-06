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

/** localStorage key of the opt-in to browser notifications. */
export const BROWSER_NOTICES_KEY = "eddi-workforce-browser-notices";
