/* ──────────────────────────────────────────────
   What a client says about a turn

   The protocol decides WHETHER a turn was dropped or a start failed
   (@eddi/core); the words are these, in the shared `conversation.*` strings.
   ────────────────────────────────────────────── */

import { ApiError, gatedToolNames, pauseReasonOf, type ApprovalStatus, type ConversationState } from "@eddi/core";
import type { Translate } from "../i18n/text";
import type { TurnFailureClass } from "./turn-failure";

/** User-facing copy explaining why a turn was dropped. */
export function skippedTurnMessage(state: ConversationState | undefined | null, t: Translate): string {
  switch (state) {
    case "AWAITING_HUMAN":
      return t("conversation.skipped.awaiting");
    case "IN_PROGRESS":
      return t("conversation.skipped.busy");
    case "ENDED":
      return t("conversation.skipped.ended");
    default:
      return t("conversation.skipped.default");
  }
}

/**
 * The sentence for a turn that did not end with a reply, by its class (see
 * `turn-failure`). `status` and `message` only choose the remedy inside
 * `notAccepted` — sign in again, shorten, wait, ask the operator.
 */
export function turnFailureMessage(
  cls: TurnFailureClass,
  status: number | null,
  t: Translate,
  detail: {
    code?: string | null;
    message?: string;
    retryable?: boolean | null;
    reason?: string | null;
    stale?: boolean;
  } = {},
): string {
  switch (cls) {
    case "ended":
      return t("conversation.send.ended");
    case "paused":
      return t("conversation.send.conflict");
    case "unknownOutcome":
      // Past the key's age limit nothing promises that sending again is safe.
      return detail.stale ? t("conversation.send.unknownOutcomeStale") : t("conversation.send.unknownOutcome");
    case "failed":
      return t("conversation.send.turnFailed");
    case "empty":
      return t("conversation.send.empty");
    case "notStored":
      // By reason: too large (507) means a new conversation is the way on; rejected
      // data, or a backend that says `retryable: false`, means sending again will
      // not help; a conflict or lost lease means reload, then send again; anything
      // else runs the turn again when sent again.
      switch ((detail.reason ?? "").toLowerCase()) {
        case "conversation_too_large":
          return t("conversation.send.notStoredTooLarge");
        case "data_rejected":
          return t("conversation.send.notStoredRejected");
        case "conflict":
        case "fenced":
          return t("conversation.send.notStoredConflict");
      }
      if (status === 507) return t("conversation.send.notStoredTooLarge");
      return detail.retryable === false ? t("conversation.send.notStoredRejected") : t("conversation.send.notStored");
    case "notAccepted":
      break;
  }
  if (detail.code === "awaiting_approval") return t("conversation.send.awaiting");
  // Refused at admission, before the turn ran: the conversation is full.
  if (detail.code?.toLowerCase() === "conversation_too_large" || status === 507) return t("conversation.send.conversationFull");
  if (detail.code) return t("conversation.send.notSent", { message: detail.message ?? "" });
  switch (status) {
    case 401:
      return t("conversation.send.sessionExpired");
    case 403:
      return t("conversation.send.forbidden");
    case 404:
      return t("conversation.send.notFound");
    case 409:
      return t("conversation.send.conflict");
    case 413:
      return t("conversation.send.tooLarge");
    case 429:
      return t("conversation.send.rateLimited");
    case 503:
      return t("conversation.send.unavailable");
    default:
      return t("conversation.send.failed");
  }
}

/** Transcript copy for a conversation that could not be started. */
export function startFailureMessage(err: unknown, t: Translate, environment?: string): string {
  if (err instanceof ApiError && err.status === 404) {
    return environment
      ? t("conversation.start.notFoundEnv", { env: environment })
      : t("conversation.start.notFound");
  }
  // 401 is "no valid credentials"; 403 is "refused". A 403 can also be the
  // server declining an origin it does not recognise, which nothing in the
  // response distinguishes, so the copy stays factual and blames nobody.
  if (err instanceof ApiError && err.status === 401) return t("conversation.start.unauthorized");
  if (err instanceof ApiError && err.status === 403) return t("conversation.start.forbidden");
  if (err instanceof ApiError && err.status === 400) return t("conversation.start.badRequest");
  return t("conversation.start.generic");
}

/**
 * One line explaining why the turn stalled. This package deliberately submits
 * no decision: deciding belongs to a reviewer, and an end user approving their
 * own gate defeats the oversight the pause exists to provide.
 */
export function pauseHeadline(status: ApprovalStatus, t: Translate): string {
  const tools = gatedToolNames(status);
  if (tools.length === 1) return t("conversation.paused.headline.tool", { tool: tools[0] ?? "" });
  if (tools.length > 1) return t("conversation.paused.headline.tools", { tools: tools.join(", ") });
  const reason = pauseReasonOf(status);
  return reason ? t("conversation.paused.headline.reason", { reason }) : t("conversation.paused.headline");
}
