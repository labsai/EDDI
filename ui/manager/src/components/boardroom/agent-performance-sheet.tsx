import { useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import {
  X,
  Calendar,
  MessageSquare,
  AlertTriangle,
  Type,
  ExternalLink,
  Pencil,
  GitCompareArrows,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { AdvisorAvatar } from "@/components/boardroom/advisor-avatar";
import { Button } from "@/components/ui/button";
import type { AgentStat } from "@/hooks/use-boardroom-analytics";

// ─── Types ───────────────────────────────────────────────────────

interface AgentPerformanceSheetProps {
  agent: AgentStat | null; // null = closed
  onClose: () => void;
  onEditAgent?: (agentId: string) => void;
  onCompare?: (agentId: string) => void;
}

// ─── Helpers ─────────────────────────────────────────────────────

function computeErrorRate(agent: AgentStat): number {
  const total = agent.contributions + agent.errors;
  if (total === 0) return 0;
  return (agent.errors / total) * 100;
}

function errorRateColorClass(rate: number): string {
  if (rate < 5) return "text-primary";
  if (rate < 15) return "text-muted-foreground";
  return "text-destructive";
}

function computeAvgLength(agent: AgentStat): number {
  if (agent.contributions === 0) return 0;
  return Math.round(agent.totalContentLength / agent.contributions);
}

function truncateId(id: string, maxLen = 20): string {
  if (id.length <= maxLen) return id;
  return `${id.slice(0, maxLen)}…`;
}

// ─── Stat Card ───────────────────────────────────────────────────

interface StatCardProps {
  icon: React.ReactNode;
  label: string;
  value: string;
  valueClassName?: string;
}

function StatCard({ icon, label, value, valueClassName }: StatCardProps) {
  return (
    <div className="rounded-lg bg-card border border-border p-4">
      <div className="flex items-center gap-2 text-muted-foreground mb-2">
        {icon}
        <span className="text-xs font-medium">{label}</span>
      </div>
      <p className={cn("text-xl font-bold text-foreground", valueClassName)}>
        {value}
      </p>
    </div>
  );
}

// ─── Contribution Bar ────────────────────────────────────────────

interface ContributionBarProps {
  contributions: number;
  errors: number;
  contributionsLabel: string;
  errorsLabel: string;
}

function ContributionBar({
  contributions,
  errors,
  contributionsLabel,
  errorsLabel,
}: ContributionBarProps) {
  const total = contributions + errors;
  const contribPct = total > 0 ? (contributions / total) * 100 : 100;
  const errorPct = total > 0 ? (errors / total) * 100 : 0;

  return (
    <div>
      {/* Bar */}
      <div className="flex h-4 w-full overflow-hidden rounded-full bg-muted">
        {contribPct > 0 && (
          <div
            className="bg-primary transition-all duration-500"
            style={{ width: `${contribPct}%` }}
          />
        )}
        {errorPct > 0 && (
          <div
            className="bg-destructive/50 transition-all duration-500"
            style={{ width: `${errorPct}%` }}
          />
        )}
      </div>

      {/* Legend */}
      <div className="mt-2 flex items-center gap-4 text-xs text-muted-foreground">
        <div className="flex items-center gap-1.5">
          <span className="inline-block h-2.5 w-2.5 rounded-full bg-primary" />
          <span>
            {contributionsLabel} ({contributions})
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <span className="inline-block h-2.5 w-2.5 rounded-full bg-destructive/50" />
          <span>
            {errorsLabel} ({errors})
          </span>
        </div>
      </div>
    </div>
  );
}

// ─── Component ───────────────────────────────────────────────────

function AgentPerformanceSheet({
  agent,
  onClose,
  onEditAgent,
  onCompare,
}: AgentPerformanceSheetProps) {
  const { t } = useTranslation();
  const isOpen = agent !== null;

  // Escape to close
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    },
    [onClose],
  );

  useEffect(() => {
    if (!isOpen) return;
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [isOpen, handleKeyDown]);

  if (!agent) return null;

  const errorRate = computeErrorRate(agent);
  const avgLength = computeAvgLength(agent);

  return (
    /* Backdrop */
    <div
      className="fixed inset-0 z-50 flex justify-end"
      role="dialog"
      aria-modal="true"
      aria-label={t(
        "boardroom.analytics.agentPerformance",
        "Agent performance",
      )}
    >
      {/* Clickable backdrop overlay */}
      <div
        className="absolute inset-0 bg-black/40 backdrop-blur-sm animate-in fade-in duration-200"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        className={cn(
          "relative z-10 flex w-[420px] max-w-full flex-col",
          "bg-card border-s border-border shadow-2xl",
          "animate-in slide-in-from-end duration-300",
        )}
      >
        {/* ── Header ────────────────────────────────────── */}
        <div className="flex items-start gap-3 border-b border-border ps-5 pe-4 py-4">
          <AdvisorAvatar
            name={agent.displayName}
            agentId={agent.agentId}
            size="lg"
          />

          <div className="flex-1 min-w-0 pt-1">
            <h2 className="text-lg font-semibold text-foreground truncate">
              {agent.displayName}
            </h2>
            <p
              className="text-xs text-muted-foreground truncate"
              title={agent.agentId}
            >
              {truncateId(agent.agentId)}
            </p>
          </div>

          <Button
            variant="ghost"
            size="icon"
            onClick={onClose}
            className="h-8 w-8 shrink-0"
            aria-label={t("common.close", "Close")}
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* ── Scrollable content ────────────────────────── */}
        <div className="flex-1 overflow-y-auto px-5 py-5 space-y-6">
          {/* Stats Grid 2×2 */}
          <div className="grid grid-cols-2 gap-3">
            <StatCard
              icon={<Calendar className="h-4 w-4" />}
              label={t("boardroom.analytics.sessions", "Sessions")}
              value={String(agent.sessions)}
            />
            <StatCard
              icon={<MessageSquare className="h-4 w-4" />}
              label={t(
                "boardroom.analytics.contributions",
                "Contributions",
              )}
              value={String(agent.contributions)}
            />
            <StatCard
              icon={<AlertTriangle className="h-4 w-4" />}
              label={t("boardroom.analytics.errorRate", "Error Rate")}
              value={`${errorRate.toFixed(1)}%`}
              valueClassName={errorRateColorClass(errorRate)}
            />
            <StatCard
              icon={<Type className="h-4 w-4" />}
              label={t("boardroom.analytics.avgLength", "Avg Length")}
              value={t(
                "boardroom.analytics.avgLengthChars",
                "{{count}} chars",
                { count: avgLength },
              )}
            />
          </div>

          {/* Contribution Breakdown */}
          <div>
            <h3 className="text-sm font-semibold text-foreground mb-3">
              {t(
                "boardroom.analytics.contributionBreakdown",
                "Contribution Breakdown",
              )}
            </h3>
            <ContributionBar
              contributions={agent.contributions}
              errors={agent.errors}
              contributionsLabel={t(
                "boardroom.analytics.contributions",
                "Contributions",
              )}
              errorsLabel={t("boardroom.analytics.errors", "Errors")}
            />
          </div>
        </div>

        {/* ── Actions (pinned bottom) ───────────────────── */}
        <div className="border-t border-border px-5 py-4 space-y-2">
          {onEditAgent && (
            <Button
              variant="outline"
              className="w-full"
              onClick={() => onEditAgent(agent.agentId)}
            >
              <Pencil className="h-4 w-4" />
              {t("boardroom.analytics.editAgent", "Edit Agent")}
            </Button>
          )}
          {onCompare && (
            <Button
              variant="outline"
              className="w-full"
              onClick={() => onCompare(agent.agentId)}
            >
              <GitCompareArrows className="h-4 w-4" />
              {t("boardroom.analytics.compareAgent", "Compare with…")}
            </Button>
          )}
          <Button variant="ghost" className="w-full" asChild>
            <a
              href={`/manage/agents?id=${agent.agentId}`}
              target="_blank"
              rel="noopener noreferrer"
            >
              <ExternalLink className="h-4 w-4" />
              {t(
                "boardroom.analytics.viewInManager",
                "View in Manager",
              )}
            </a>
          </Button>
        </div>
      </div>
    </div>
  );
}

export { AgentPerformanceSheet };
export type { AgentPerformanceSheetProps };
