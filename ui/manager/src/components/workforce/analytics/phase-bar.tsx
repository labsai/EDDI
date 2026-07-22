import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import type { PhaseCount } from "@/hooks/use-workforce-analytics";
import { ENTRY_TYPE_INFO } from "@/lib/api/groups";
import type { TranscriptEntryType } from "@/lib/api/groups";

interface PhaseBarProps {
  data: PhaseCount[];
}

/** Opacity classes cycling through primary at various strengths */
const PHASE_CLASSES: Record<TranscriptEntryType, string> = {
  QUESTION: "bg-primary/20",
  OPINION: "bg-primary/40",
  CRITIQUE: "bg-primary/55",
  REVISION: "bg-primary/30",
  CHALLENGE: "bg-primary/70",
  DEFENSE: "bg-primary/50",
  ARGUMENT: "bg-primary/60",
  REBUTTAL: "bg-primary/45",
  SYNTHESIS: "bg-primary",
  ERROR: "bg-destructive/60",
  SKIPPED: "bg-muted-foreground/30",
  PLAN: "bg-primary/35",
  TASK_RESULT: "bg-primary/75",
  VERIFICATION: "bg-primary/65",
};

function PhaseBar({ data }: PhaseBarProps) {
  const { t } = useTranslation();

  const total = useMemo(
    () => data.reduce((sum, d) => sum + d.count, 0),
    [data],
  );

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.phaseDistribution", "Phase Distribution")}
      </h3>

      {data.length === 0 || total === 0 ? (
        <p className="text-sm text-muted-foreground">
          {t("analyticsPage.noPhases", "No phase data available.")}
        </p>
      ) : (
        <>
          {/* Stacked bar */}
          <div
            className="flex h-6 w-full overflow-hidden rounded-full"
            role="img"
            aria-label={t("analyticsPage.phaseChart", "Phase distribution")}
          >
            {data.map((item) => {
              const pct = (item.count / total) * 100;
              if (pct < 0.5) return null;
              return (
                <div
                  key={item.type}
                  className={cn(
                    "transition-all duration-700",
                    PHASE_CLASSES[item.type] ?? "bg-muted",
                  )}
                  style={{ width: `${pct}%` }}
                  title={`${ENTRY_TYPE_INFO[item.type]?.label ?? item.type}: ${item.count}`}
                />
              );
            })}
          </div>

          {/* Legend */}
          <div className="mt-4 flex flex-wrap gap-x-4 gap-y-1">
            {data.map((item) => (
              <div key={item.type} className="flex items-center gap-1.5">
                <div
                  className={cn(
                    "h-2.5 w-2.5 rounded-full",
                    PHASE_CLASSES[item.type] ?? "bg-muted",
                  )}
                />
                <span className="text-xs text-muted-foreground">
                  {ENTRY_TYPE_INFO[item.type]?.label ?? item.type} ({item.count})
                </span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

export { PhaseBar };
export type { PhaseBarProps };
