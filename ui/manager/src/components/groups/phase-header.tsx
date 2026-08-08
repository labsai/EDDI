import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronRight, Users, Zap, GitMerge } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { PhaseType, TurnOrder } from "@/lib/api/groups";
import type { ConvergenceProgress } from "@/hooks/use-group-discussion-stream";

interface PhaseHeaderProps {
  name: string;
  type: PhaseType;
  turnOrder?: TurnOrder;
  entryCount: number;
  isActive?: boolean;
  defaultExpanded?: boolean;
  /**
   * Convergence result for this phase (EDDI I2), when one was checked. Shown on
   * the header rather than inside the entries because it is a statement about the
   * phase — how close the members came to agreeing, and whether that ended the
   * repeats early — not another contribution to it.
   */
  convergence?: ConvergenceProgress;
  children: React.ReactNode;
}

const PHASE_ICONS: Record<PhaseType, string> = {
  OPINION: "💬",
  CRITIQUE: "🔍",
  REVISION: "✏️",
  CHALLENGE: "⚔️",
  DEFENSE: "🛡️",
  ARGUE: "📢",
  REBUTTAL: "↩️",
  SYNTHESIS: "⭐",
  PLAN: "📋",
  EXECUTE: "⚡",
  VERIFY: "✅",
};

export function PhaseHeader({
  name,
  type,
  turnOrder,
  entryCount,
  isActive,
  defaultExpanded = true,
  convergence,
  children,
}: PhaseHeaderProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(defaultExpanded);
  const icon = PHASE_ICONS[type] || "📋";

  return (
    <div
      className={cn(
        "rounded-xl border",
        type === "SYNTHESIS"
          ? "border-primary/30 bg-primary/5"
          : "border-border bg-card",
        isActive && "ring-2 ring-primary/30"
      )}
      data-testid={`phase-section-${name.replace(/\s+/g, "-").toLowerCase()}`}
    >
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-3 p-3 text-start transition-colors hover:bg-secondary/30 rounded-t-xl"
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
        )}

        <span className="text-base">{icon}</span>

        <span className="text-sm font-semibold text-foreground flex-1">
          {name}
        </span>

        <div className="flex items-center gap-2">
          {turnOrder === "PARALLEL" && (
            <Badge variant="outline" className="text-[10px] px-1.5 py-0">
              <Zap className="me-0.5 h-2.5 w-2.5" /> Parallel
            </Badge>
          )}
          <Badge variant="secondary" className="text-[10px] px-1.5 py-0">
            <Users className="me-0.5 h-2.5 w-2.5" /> {entryCount}
          </Badge>
        </div>
      </button>

      {/* Convergence result — outside the collapsible body, because "this phase
          stopped early" is the kind of thing you need to see with the phase
          collapsed. */}
      {convergence && (
        <div
          className={cn(
            "flex items-start gap-2 border-t px-3 py-2",
            convergence.converged
              ? "border-violet-500/30 bg-violet-500/5"
              : "border-border bg-secondary/20",
          )}
          data-testid={`phase-convergence-${convergence.phaseIndex}`}
        >
          <GitMerge className="mt-0.5 h-3 w-3 shrink-0 text-violet-500" aria-hidden="true" />
          <div className="min-w-0 text-[11px] text-muted-foreground">
            <span className="font-medium text-foreground">
              {convergence.converged
                ? t("groups.convergenceReached", "Converged")
                : t("groups.convergenceChecked", "Convergence check")}
            </span>
            {convergence.agreementScore != null && (
              <span className="ms-1.5 tabular-nums">
                {t("groups.convergenceScore", "agreement {{score}}", {
                  score: convergence.agreementScore.toFixed(2),
                })}
              </span>
            )}
            {convergence.repeatsSkipped != null && convergence.repeatsSkipped > 0 && (
              <span className="ms-1.5">
                {t("groups.convergenceSkipped", "· {{skipped}} further round(s) skipped", {
                  skipped: convergence.repeatsSkipped,
                })}
              </span>
            )}
            {convergence.reason && <p className="mt-0.5">{convergence.reason}</p>}
          </div>
        </div>
      )}

      {/* Entries */}
      {expanded && (
        <div className="border-t border-border p-2 space-y-1">
          {children}
        </div>
      )}
    </div>
  );
}
