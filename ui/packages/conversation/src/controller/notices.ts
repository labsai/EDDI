/* ──────────────────────────────────────────────
   What a client says about a turn

   The protocol decides WHETHER a turn was dropped or a start failed
   (@eddi/core); the words are these, in the shared `conversation.*` strings.
   ────────────────────────────────────────────── */

import { ApiError, gatedToolNames, pauseReasonOf, type ApprovalStatus, type ConversationState } from "@eddi/core";
import type { Translate } from "../i18n/text";

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
