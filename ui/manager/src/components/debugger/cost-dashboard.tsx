import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAuditTrail, type AuditEntry } from "@/lib/api/audit";
import { useConversationCosts, useCacheStats } from "@/hooks/use-tool-metrics";
import { cn } from "@/lib/utils";
import { Coins, Zap, TrendingUp, Server, BarChart3 } from "lucide-react";
import { useMemo } from "react";

// ==================== Component ====================

interface CostDashboardProps {
  conversationId: string | null;
  isActive?: boolean;
}

export function CostDashboard({ conversationId, isActive = false }: CostDashboardProps) {
  const { t } = useTranslation();

  // Fetch costs from the tool metrics API
  const { data: costs } = useConversationCosts(conversationId, isActive);

  // Fetch cache stats
  const { data: cacheStats } = useCacheStats(isActive);

  // Fetch audit entries for token-level detail
  const { data: auditEntries } = useQuery({
    queryKey: ["audit", "costDash", conversationId],
    queryFn: () => getAuditTrail(conversationId!, 0, 200),
    enabled: !!conversationId,
    staleTime: 10_000,
  });

  // Compute token metrics from audit entries
  const tokenMetrics = useMemo(() => {
    if (!auditEntries?.length) return null;
    return computeTokenMetrics(auditEntries);
  }, [auditEntries]);

  const latestTurn = tokenMetrics?.turns[tokenMetrics.turns.length - 1];

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="cost-dashboard">
      {/* This Turn */}
      {latestTurn && (
        <MetricSection
          title={t("costDashboard.thisTurn", "This Turn")}
          icon={<Zap className="h-3.5 w-3.5" />}
        >
          <MetricRow
            label={t("costDashboard.tokens", "Tokens")}
            value={`${fmtNum(latestTurn.inputTokens)} ${t("costDashboard.in", "in")} · ${fmtNum(latestTurn.outputTokens)} ${t("costDashboard.out", "out")} · ${fmtNum(latestTurn.totalTokens)} ${t("costDashboard.total", "total")}`}
          />
          {latestTurn.cost > 0 && (
            <MetricRow
              label={t("costDashboard.cost", "Cost")}
              value={fmtCost(latestTurn.cost)}
            />
          )}
          <MetricRow
            label={t("costDashboard.duration", "Duration")}
            value={fmtDuration(latestTurn.durationMs)}
          />
          {latestTurn.modelName && (
            <MetricRow
              label={t("costDashboard.model", "Model")}
              value={latestTurn.modelName}
            />
          )}
        </MetricSection>
      )}

      {/* Conversation Total */}
      {tokenMetrics && (
        <MetricSection
          title={t("costDashboard.conversationTotal", "Conversation Total")}
          icon={<TrendingUp className="h-3.5 w-3.5" />}
        >
          <MetricRow
            label={t("costDashboard.tokens", "Tokens")}
            value={`${fmtNum(tokenMetrics.totalInput)} ${t("costDashboard.in", "in")} · ${fmtNum(tokenMetrics.totalOutput)} ${t("costDashboard.out", "out")} · ${fmtNum(tokenMetrics.totalTokens)} ${t("costDashboard.total", "total")}`}
          />
          <MetricRow
            label={t("costDashboard.cost", "Cost")}
            value={fmtCost(tokenMetrics.totalCost)}
          />
          {costs?.totalToolCalls != null && costs.totalToolCalls > 0 && (
            <MetricRow
              label={t("costDashboard.toolCalls", "Tool Calls")}
              value={`${costs.totalToolCalls}`}
            />
          )}
        </MetricSection>
      )}

      {/* Rate Limits / Quota Gauges */}
      {costs?.toolUsage && Object.keys(costs.toolUsage).length > 0 && (
        <MetricSection
          title={t("costDashboard.toolUsage", "Tool Usage")}
          icon={<BarChart3 className="h-3.5 w-3.5" />}
        >
          {Object.entries(costs.toolUsage).map(([tool, usage]) => (
            <div key={tool} className="space-y-0.5">
              <div className="flex items-center justify-between text-[11px]">
                <span className="font-medium text-foreground truncate">{tool}</span>
                <span className="text-muted-foreground font-mono">
                  {usage.calls} {t("costDashboard.calls", "calls")} · {fmtCost(usage.totalCost)}
                </span>
              </div>
            </div>
          ))}
        </MetricSection>
      )}

      {/* Cache Stats */}
      {cacheStats && cacheStats.hitRate > 0 && (
        <MetricSection
          title={t("costDashboard.cacheStats", "Cache")}
          icon={<Server className="h-3.5 w-3.5" />}
        >
          <div className="space-y-1">
            <div className="flex items-center justify-between text-[11px]">
              <span className="text-foreground">{t("costDashboard.hitRate", "Hit Rate")}</span>
              <span className="font-mono text-muted-foreground">
                {(cacheStats.hitRate * 100).toFixed(1)}%
              </span>
            </div>
            <ProgressBar
              value={cacheStats.hitRate}
              variant="success"
            />
          </div>
        </MetricSection>
      )}

      {/* Empty state */}
      {!tokenMetrics && !costs && (
        <div className="flex flex-col items-center gap-2 py-6 text-center">
          <Coins className="h-8 w-8 text-muted-foreground/30" />
          <p className="text-sm text-muted-foreground">
            {t("costDashboard.empty", "Send a message to see cost metrics")}
          </p>
        </div>
      )}
    </div>
  );
}

// ==================== Sub-components ====================

function MetricSection({
  title,
  icon,
  children,
}: {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-border bg-card/50 p-2.5 space-y-1.5">
      <div className="flex items-center gap-1.5 text-xs font-semibold text-foreground/80">
        {icon}
        {title}
      </div>
      <div className="space-y-1">{children}</div>
    </div>
  );
}

function MetricRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between text-[11px]">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-mono text-foreground">{value}</span>
    </div>
  );
}

function ProgressBar({
  value,
  max = 1,
  variant = "default",
}: {
  value: number;
  max?: number;
  variant?: "default" | "success" | "warning" | "critical";
}) {
  const pct = Math.min((value / max) * 100, 100);
  const colorClass =
    variant === "critical"
      ? "bg-destructive"
      : variant === "warning"
        ? "bg-amber-500"
        : variant === "success"
          ? "bg-emerald-500"
          : "bg-primary";

  return (
    <div className="h-1.5 w-full rounded-full bg-muted/40 overflow-hidden">
      <div
        className={cn("h-full rounded-full transition-all duration-500", colorClass)}
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}

// ==================== Helpers ====================

interface TurnMetrics {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  cost: number;
  durationMs: number;
  modelName: string | null;
}

interface TokenMetricsResult {
  turns: TurnMetrics[];
  totalInput: number;
  totalOutput: number;
  totalTokens: number;
  totalCost: number;
}

function computeTokenMetrics(entries: AuditEntry[]): TokenMetricsResult {
  // Group by stepIndex
  const byStep = new Map<number, AuditEntry[]>();
  for (const entry of entries) {
    const step = entry.stepIndex ?? 0;
    if (!byStep.has(step)) byStep.set(step, []);
    byStep.get(step)!.push(entry);
  }

  const turns: TurnMetrics[] = [];
  let totalInput = 0, totalOutput = 0, totalCost = 0;

  const sortedSteps = Array.from(byStep.entries()).sort(([a], [b]) => a - b);
  for (const [, stepEntries] of sortedSteps) {
    let inputTk = 0, outputTk = 0, cost = 0, duration = 0;
    let model: string | null = null;

    for (const entry of stepEntries) {
      const llm = entry.llmDetail as Record<string, unknown> | null;
      if (llm) {
        const tokenUsage = llm.tokenUsage as Record<string, number> | undefined;
        if (tokenUsage) {
          inputTk += tokenUsage.inputTokens ?? 0;
          outputTk += tokenUsage.outputTokens ?? 0;
        }
        if (llm.modelName) model = String(llm.modelName);
      }
      cost += entry.cost ?? 0;
      duration += entry.durationMs ?? 0;
    }

    turns.push({
      inputTokens: inputTk,
      outputTokens: outputTk,
      totalTokens: inputTk + outputTk,
      cost,
      durationMs: duration,
      modelName: model,
    });

    totalInput += inputTk;
    totalOutput += outputTk;
    totalCost += cost;
  }

  return {
    turns,
    totalInput,
    totalOutput,
    totalTokens: totalInput + totalOutput,
    totalCost,
  };
}

function fmtNum(n: number): string {
  return n.toLocaleString();
}

function fmtCost(n: number): string {
  if (n === 0) return "$0.00";
  if (n < 0.01) return `$${n.toFixed(4)}`;
  return `$${n.toFixed(2)}`;
}

function fmtDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}
