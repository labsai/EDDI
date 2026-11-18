/* ──────────────────────────────────────────────
   EDDI Chat — conversation ratings
   The rating protocol (profile parsing, the client, the `{error: "<CODE>"}`
   envelope) is in @eddi/core. What stays here is the widget's: the channel it
   names itself by, and what it tells the person when a rating is refused.
   ────────────────────────────────────────────── */

import { ApiError, createRatingClient, defaultHttp, ratingErrorCode, type RatingClient } from "@eddi/core";
import { t } from "@/i18n";

/** How this widget names itself to the server (`channel`). */
export const RATING_CHANNEL = "chat-ui";

/** The real backend; the demo has an in-memory twin (demo-api.ts). */
export const httpRatingClient: RatingClient = createRatingClient(defaultHttp, RATING_CHANNEL);

/** What to tell the person when a rating could not be saved. */
export function ratingErrorMessage(err: unknown): string {
  switch (ratingErrorCode(err)) {
    case "RATING_WINDOW_CLOSED":
      return t("rating.error.windowClosed");
    case "RATING_DISABLED":
      return t("rating.error.disabled");
    case "INVALID_STEP":
      return t("rating.error.invalidStep");
    case "COMMENT_TOO_LONG":
      return t("rating.error.commentTooLong");
    case "INVALID_TAG":
    case "INVALID_SCORE":
    case "COMMENT_NOT_ALLOWED":
      return t("rating.error.invalid");
    case "NOT_CONVERSATION_OWNER":
      return t("rating.error.notOwner");
    case "CONVERSATION_NOT_FOUND":
      return t("rating.error.notFound");
    case "processing_restricted":
      return t("rating.error.restricted");
    default:
      if (err instanceof ApiError && err.status === 401) return t("rating.error.session");
      return t("rating.error.generic");
  }
}
