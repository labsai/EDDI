import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import type { OutcomeCount } from "@/hooks/use-workforce-analytics";
import type { GroupConversationState } from "@/lib/api/groups";

interface OutcomeRingProps {
  data: OutcomeCount[];
  total: number;
  selected?: GroupConversationState | null;
  onSelect?: (state: GroupConversationState | null) => void;
}

const OUTCOME_COLORS: Record<GroupConversationState, string> = {
  COMPLETED: "var(--color-primary)",
  FAILED: "var(--color-destructive)",
  IN_PROGRESS: "color-mix(in srgb, var(--color-primary) 40%, transparent)",
  SYNTHESIZING: "color-mix(in srgb, var(--color-primary) 60%, transparent)",
  CREATED: "color-mix(in srgb, currentColor 20%, transparent)",
  CANCELLED: "color-mix(in srgb, currentColor 30%, transparent)",
  AWAITING_APPROVAL: "color-mix(in srgb, var(--color-primary) 25%, transparent)",
  AWAITING_HUMAN_INPUT: "color-mix(in srgb, var(--color-primary) 70%, transparent)",
  CLOSED: "color-mix(in srgb, currentColor 15%, transparent)",
};

const OUTCOME_DOT_CLASSES: Record<GroupConversationState, string> = {
  COMPLETED: "bg-primary",
  FAILED: "bg-destructive",
  IN_PROGRESS: "bg-primary/40",
  SYNTHESIZING: "bg-primary/60",
  CREATED: "bg-muted-foreground/20",
  CANCELLED: "bg-muted-foreground/30",
  AWAITING_APPROVAL: "bg-primary/25",
  AWAITING_HUMAN_INPUT: "bg-primary/70",
  CLOSED: "bg-muted-foreground/15",
};

const STATE_LABELS: Record<GroupConversationState, string> = {
  COMPLETED: "Completed",
  FAILED: "Failed",
  IN_PROGRESS: "In Progress",
  SYNTHESIZING: "Synthesizing",
  CREATED: "Created",
  CANCELLED: "Cancelled",
  AWAITING_APPROVAL: "Pending",
  AWAITING_HUMAN_INPUT: "Awaiting your turn",
  CLOSED: "Closed",
};

function OutcomeRing({ data, total, selected, onSelect }: OutcomeRingProps) {
  const { t } = useTranslation();

  const gradient = useMemo(() => {
    if (data.length === 0 || total === 0) return "var(--color-muted)";

    const segments: string[] = [];
    let cumulative = 0;

    for (const item of data) {
      const pct = (item.count / total) * 100;
      const color = OUTCOME_COLORS[item.state] ?? "var(--color-muted)";
      segments.push(`${color} ${cumulative}% ${cumulative + pct}%`);
      cumulative += pct;
    }

    return `conic-gradient(${segments.join(", ")})`;
  }, [data, total]);

  return (
    <div className="rounded-xl border border-border bg-card p-5 br-card-premium">
      <h3 className="mb-4 text-sm font-semibold">
        {t("analyticsPage.outcomes", "Outcomes")}
      </h3>

      <div className="flex flex-col items-center gap-4">
        {/* Donut */}
        <div className="relative">
          <div
            className="h-40 w-40 rounded-full"
            style={{ background: gradient }}
            role="img"
            aria-label={t("analyticsPage.outcomeChart", "Outcome distribution: {{total}} total", { total })}
          />
          {/* Inner hole */}
          <div className="absolute inset-3 flex items-center justify-center rounded-full bg-card">
            <div className="text-center">
              <p className="text-xl font-bold">{total}</p>
              <p className="text-xs text-muted-foreground">
                {t("analyticsPage.total", "Total")}
              </p>
            </div>
          </div>
        </div>

        {/* Legend */}
        <div className="flex flex-wrap justify-center gap-x-4 gap-y-1">
          {data.map((item) => {
            const isSelected = selected === item.state;
            const dimmed = selected != null && !isSelected;
            return (
              <button
                key={item.state}
                type="button"
                className={cn(
                  "flex cursor-pointer items-center gap-1.5 transition-opacity",
                  isSelected && "ring-2 ring-primary rounded-md ps-1.5 pe-1.5 py-0.5 bg-primary/5",
                  dimmed && "opacity-50",
                )}
                onClick={() => onSelect?.(isSelected ? null : item.state)}
              >
                <div
                  className={cn(
                    "h-2.5 w-2.5 rounded-full",
                    OUTCOME_DOT_CLASSES[item.state] ?? "bg-muted",
                  )}
                />
                <span className="text-xs text-muted-foreground">
                  {STATE_LABELS[item.state] ?? item.state} ({item.count})
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export { OutcomeRing };
export type { OutcomeRingProps };
