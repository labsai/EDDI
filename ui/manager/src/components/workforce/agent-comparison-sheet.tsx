import { useEffect, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { AdvisorAvatar } from "@/components/workforce/advisor-avatar";
import type { AgentStat } from "@/hooks/use-workforce-analytics";

// ─── Types ───────────────────────────────────────────────────────

interface AgentComparisonSheetProps {
  agents: [AgentStat, AgentStat] | null;
  onClose: () => void;
}

interface MetricRow {
  label: string;
  leftValue: number;
  rightValue: number;
  format: (v: number) => string;
  lowerIsBetter?: boolean;
}

// ─── Component ───────────────────────────────────────────────────

function AgentComparisonSheet({ agents, onClose }: AgentComparisonSheetProps) {
  const { t } = useTranslation();

  const handleEscape = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    },
    [onClose],
  );

  useEffect(() => {
    if (!agents) return;
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [agents, handleEscape]);

  if (!agents) return null;

  const [left, right] = agents;

  const errorRate = (a: AgentStat) =>
    a.contributions + a.errors > 0
      ? (a.errors / (a.contributions + a.errors)) * 100
      : 0;

  const avgLength = (a: AgentStat) =>
    a.contributions > 0
      ? Math.round(a.totalContentLength / a.contributions)
      : 0;

  const formatNum = (v: number) => String(v);
  const formatPct = (v: number) => `${v.toFixed(1)}%`;
  const formatChars = (v: number) =>
    v >= 1000 ? `${(v / 1000).toFixed(1)}k` : String(v);

  const metrics: MetricRow[] = [
    {
      label: t("Workforce.comparison.sessions", "Sessions"),
      leftValue: left.sessions,
      rightValue: right.sessions,
      format: formatNum,
    },
    {
      label: t("Workforce.comparison.contributions", "Contributions"),
      leftValue: left.contributions,
      rightValue: right.contributions,
      format: formatNum,
    },
    {
      label: t("Workforce.comparison.errorRate", "Error Rate"),
      leftValue: errorRate(left),
      rightValue: errorRate(right),
      format: formatPct,
      lowerIsBetter: true,
    },
    {
      label: t("Workforce.comparison.avgResponse", "Avg Response"),
      leftValue: avgLength(left),
      rightValue: avgLength(right),
      format: formatChars,
    },
    {
      label: t("Workforce.comparison.totalContent", "Total Content"),
      leftValue: left.totalContentLength,
      rightValue: right.totalContentLength,
      format: formatChars,
    },
  ];

  // Count wins
  let leftWins = 0;
  let rightWins = 0;
  for (const m of metrics) {
    if (m.leftValue === m.rightValue) continue;
    const leftBetter = m.lowerIsBetter
      ? m.leftValue < m.rightValue
      : m.leftValue > m.rightValue;
    if (leftBetter) leftWins++;
    else rightWins++;
  }

  const isTie = leftWins === rightWins;
  const winner = leftWins > rightWins ? left : right;
  const winCount = Math.max(leftWins, rightWins);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/60"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        className={cn(
          "relative z-10 w-full max-w-3xl rounded-2xl border border-border bg-background shadow-2xl",
          "animate-in fade-in-0 zoom-in-95 duration-200",
        )}
        role="dialog"
        aria-modal="true"
        aria-label={t("Workforce.comparison.title", "Agent Comparison")}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border ps-6 pe-4 py-4">
          <h2 className="text-lg font-semibold text-foreground">
            {t("Workforce.comparison.title", "Agent Comparison")}
          </h2>
          <Button
            variant="ghost"
            size="icon"
            onClick={onClose}
            className="h-8 w-8"
            aria-label={t("Workforce.comparison.close", "Close")}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Agent headers */}
        <div className="grid grid-cols-2 gap-4 ps-6 pe-6 pt-5 pb-3">
          {[left, right].map((agent) => (
            <div
              key={agent.agentId}
              className="flex items-center gap-3"
            >
              <AdvisorAvatar
                name={agent.displayName}
                agentId={agent.agentId}
                size="md"
              />
              <div className="min-w-0">
                <p className="font-semibold text-sm text-foreground truncate">
                  {agent.displayName}
                </p>
                <p className="text-[10px] text-muted-foreground truncate">
                  {agent.agentId.slice(0, 12)}…
                </p>
              </div>
            </div>
          ))}
        </div>

        {/* Metric rows */}
        <div className="ps-6 pe-6 pb-2 space-y-1">
          {metrics.map((m) => {
            const leftBetter = m.lowerIsBetter
              ? m.leftValue < m.rightValue
              : m.leftValue > m.rightValue;
            const rightBetter = m.lowerIsBetter
              ? m.rightValue < m.leftValue
              : m.rightValue > m.leftValue;
            const total = m.leftValue + m.rightValue || 1;
            const leftPct = (m.leftValue / total) * 100;

            return (
              <div key={m.label}>
                <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-4 py-2">
                  <span
                    className={cn(
                      "text-end text-sm tabular-nums",
                      leftBetter
                        ? "text-primary font-semibold"
                        : "text-foreground",
                    )}
                  >
                    {m.format(m.leftValue)}
                  </span>
                  <span className="text-xs text-muted-foreground text-center min-w-[100px]">
                    {m.label}
                  </span>
                  <span
                    className={cn(
                      "text-start text-sm tabular-nums",
                      rightBetter
                        ? "text-primary font-semibold"
                        : "text-foreground",
                    )}
                  >
                    {m.format(m.rightValue)}
                  </span>
                </div>
                {/* Visual bar */}
                <div className="h-1.5 rounded-full bg-muted overflow-hidden flex">
                  <div
                    className="bg-primary rounded-s-full transition-all duration-300"
                    style={{ width: `${leftPct}%` }}
                  />
                  <div
                    className="bg-primary/30 rounded-e-full transition-all duration-300"
                    style={{ width: `${100 - leftPct}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>

        {/* Summary */}
        <div className="border-t border-border ps-6 pe-6 py-4">
          <p className="text-sm text-muted-foreground text-center">
            {isTie ? (
              t("Workforce.comparison.evenlyMatched", "Both agents are evenly matched")
            ) : (
              <>
                <span className="font-semibold text-primary">
                  {winner.displayName}
                </span>{" "}
                {t("Workforce.comparison.leadsIn", "leads in {{count}} of 5 metrics", {
                  count: winCount,
                })}
              </>
            )}
          </p>
        </div>
      </div>
    </div>
  );
}

export { AgentComparisonSheet };
