/* ──────────────────────────────────────────────
   EDDI Chat — what the widget says about a turn
   The protocol decides WHETHER a turn was dropped (`isSkippedTurn` in
   @eddi/core); the words are the widget's own.
   ────────────────────────────────────────────── */

import type { ConversationState } from "@eddi/core";
import { t } from "@/i18n";

/** User-facing copy explaining why a turn was dropped. */
export function skippedTurnMessage(state: ConversationState | undefined): string {
  switch (state) {
    case "AWAITING_HUMAN":
      return t("skipped.awaiting");
    case "IN_PROGRESS":
      return t("skipped.busy");
    case "ENDED":
      return t("skipped.ended");
    default:
      return t("skipped.default");
  }
}
