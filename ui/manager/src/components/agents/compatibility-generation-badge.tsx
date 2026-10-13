import { useTranslation } from "react-i18next";
import { GitBranch } from "lucide-react";

/**
 * The agent version's compatibility generation, shown beside the version
 * picker in the same height and weight — a quiet qualifier of the version, not
 * a second headline. The label stays short; what a generation means is in the
 * tooltip. Renders nothing for a version stored before version following (no
 * generation), which is compatible only with itself.
 */
export function CompatibilityGenerationBadge({ generation }: { generation: number | null | undefined }) {
  const { t } = useTranslation();
  if (typeof generation !== "number") return null;
  const tooltip = t(
    "agentVersioning.generationTooltip",
    "Compatibility generation {{generation}}: versions of this agent with the same generation are compatible, so a running conversation moves to the newest deployed one on its next turn. A breaking save starts a new generation.",
    { generation },
  );
  return (
    <span
      className="inline-flex h-[26px] items-center gap-1.5 rounded-md border border-border px-2 text-xs font-medium text-muted-foreground"
      title={tooltip}
      aria-label={tooltip}
      data-testid="compatibility-generation-badge"
    >
      <GitBranch className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      {t("agentVersioning.generationBadge", "Generation {{generation}}", { generation })}
    </span>
  );
}
