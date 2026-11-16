/* ──────────────────────────────────────────────
   EDDI Chat — Human-in-the-Loop copy
   The protocol (approval status, cancel, deadline maths, poll cadence) is in
   @eddi/core. What the widget SAYS about a pause is here, in its own strings.

   This widget deliberately does NOT submit decisions. Deciding belongs to a
   reviewer (Manager UI, via /agents/pending-approvals); an end user approving
   their own gate defeats the oversight the pause exists to provide.
   ────────────────────────────────────────────── */

import { gatedToolNames, pauseReasonOf, type ApprovalStatus } from "@eddi/core";
import { t } from "@/i18n";

/** One line explaining why the turn stalled. */
export function pauseHeadline(status: ApprovalStatus): string {
  const tools = gatedToolNames(status);
  if (tools.length === 1) return t("paused.headline.tool", { tool: tools[0] });
  if (tools.length > 1) {
    return t("paused.headline.tools", { tools: tools.join(", ") });
  }

  const reason = pauseReasonOf(status);
  return reason ? t("paused.headline.reason", { reason }) : t("paused.headline");
}
