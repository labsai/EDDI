import { useTranslation } from "react-i18next";
import { ArrowDownToLine } from "lucide-react";
import { describeHandoffSource, type HandoffOrigin } from "@/lib/api/team-handoffs";

interface HandoffOriginBadgeProps {
  origin: HandoffOrigin | undefined;
  /** agent id → name; groups fall back to their id. */
  nameOf?: (id: string) => string;
}

/**
 * "from <team or agent>" on a backlog row whose task arrived by handoff.
 * Renders nothing for a task the team authored itself.
 */
export function HandoffOriginBadge({ origin, nameOf }: HandoffOriginBadgeProps) {
  const { t } = useTranslation();
  if (!origin) return null;
  const source = describeHandoffSource(origin, nameOf);
  const edited = origin.intake === "APPROVED_EDITED";
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-1.5 py-0 text-[9px] font-medium text-primary"
      title={
        edited
          ? t("teamHandoff.badgeTitleEdited", "Handed off from {{source}} (hop {{n}}), edited on acceptance", { source, n: origin.hop })
          : t("teamHandoff.badgeTitle", "Handed off from {{source}} (hop {{n}})", { source, n: origin.hop })
      }
      data-testid={`handoff-origin-${origin.handoffId}`}
    >
      <ArrowDownToLine className="h-2.5 w-2.5" aria-hidden="true" />
      {t("teamHandoff.badge", "from {{source}}", { source })}
    </span>
  );
}
