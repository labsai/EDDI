import { useTranslation } from "react-i18next";
import { GitBranch } from "lucide-react";

/**
 * The agent version's compatibility generation, compactly. Renders nothing for
 * a version stored before version following (no generation), which is
 * compatible only with itself.
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
      className="inline-flex items-center gap-1 rounded-md bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground"
      title={tooltip}
      aria-label={tooltip}
      data-testid="compatibility-generation-badge"
    >
      <GitBranch className="h-3 w-3" aria-hidden="true" />
      {t("agentVersioning.generationBadge", "compat. gen {{generation}}", { generation })}
    </span>
  );
}
