import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { getAuditTrail, type AuditEntry } from "@/lib/api/audit";
import { useConversationCosts } from "@/hooks/use-tool-metrics";
import { cn, formatDuration } from "@/lib/utils";
import { Coins, Clock, Activity, Database, ArrowUp, ArrowDown } from "lucide-react";
import { useMemo } from "react";

// ==================== Component ====================

interface CostDashboardProps {
  conversationId: string | null;
  isActive?: boolean;
}

export function CostDashboard({ conversationId, isActive = false }: CostDashboardProps) {
  const { t } = useTranslation();
  const { data: costs, isError: costsError } = useConversationCosts(conversationId, isActive);

  const { data: auditEntries } = useQuery({
    queryKey: ["audit", "costDash", conversationId],
    queryFn: () => getAuditTrail(conversationId!, 0, 200),
    enabled: !!conversationId,
    staleTime: 10_000,
  });

  const tokenMetrics = useMemo(() => {
    if (!auditEntries?.length) return null;
    return computeTokenMetrics(auditEntries);
  }, [auditEntries]);

  if (costsError && !costs && !tokenMetrics) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center" data-testid="cost-dashboard-error">
        <Activity className="h-8 w-8 text-destructive/50" />
        <p className="text-sm text-muted-foreground">{t("costDashboard.error", "Unable to load cost data")}</p>
      </div>
    );
  }

  if (!tokenMetrics && !costs) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center">
        <Coins className="h-8 w-8 text-muted-foreground/30" />
        <p className="text-sm text-muted-foreground">{t("costDashboard.empty", "Send a message to see cost metrics")}</p>
      </div>
    );
  }

  const turnsCount = tokenMetrics?.turns.length ?? 0;
  const avgLatency = turnsCount > 0 ? (tokenMetrics?.turns.reduce((sum, tr) => sum + tr.durationMs, 0) ?? 0) / turnsCount : 0;
  
  const totalIn = tokenMetrics?.totalInput ?? 0;
  const totalOut = tokenMetrics?.totalOutput ?? 0;
  const totalTokens = totalIn + totalOut;
  const inPct = totalTokens > 0 ? (totalIn / totalTokens) * 100 : 0;
  const outPct = totalTokens > 0 ? (totalOut / totalTokens) * 100 : 0;

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="cost-dashboard">
      {/* 4 Stat Cards */}
      <div className="grid grid-cols-4 gap-2">
        <StatCard
          icon={<Coins className="h-3 w-3" aria-hidden="true" />}
          label={t("costDashboard.totalCost", "Total Cost")}
          value={fmtCost(tokenMetrics?.totalCost ?? costs?.totalCost ?? 0)}
        />
        <StatCard
          icon={<Database className="h-3 w-3" aria-hidden="true" />}
          label={t("costDashboard.totalTokens", "Total Tokens")}
          value={fmtNum(totalTokens)}
        />
        <StatCard
          icon={<Activity className="h-3 w-3" aria-hidden="true" />}
          label={t("costDashboard.turns", "Turns")}
          value={turnsCount.toString()}
        />
        <StatCard
          icon={<Clock className="h-3 w-3" aria-hidden="true" />}
          label={t("costDashboard.avgLatency", "Avg Latency")}
          value={formatDuration(avgLatency)}
        />
      </div>

      {/* Token Distribution Bar */}
      {totalTokens > 0 && (
        <div className="rounded-lg border border-border bg-card p-2 space-y-1.5">
          <div className="flex justify-between text-[10px] font-medium">
            <span className="text-muted-foreground flex items-center gap-1">
              <ArrowUp className="h-3 w-3" aria-hidden="true" />
              {t("costDashboard.input", "Input")}: {fmtNum(totalIn)}
            </span>
            <span className="text-primary flex items-center gap-1">
              {t("costDashboard.output", "Output")}: {fmtNum(totalOut)}
              <ArrowDown className="h-3 w-3" aria-hidden="true" />
            </span>
          </div>
          <div
            className="h-2 w-full rounded-full flex overflow-hidden bg-muted"
            role="meter"
            aria-label={t("costDashboard.tokenDistribution", "Token distribution")}
            aria-valuenow={totalIn}
            aria-valuemin={0}
            aria-valuemax={totalTokens}
          >
            <div className="bg-muted-foreground/40 transition-all" style={{ width: `${inPct}%` }} />
            <div className="bg-primary transition-all" style={{ width: `${outPct}%` }} />
          </div>
        </div>
      )}

      {/* Per-Turn Table */}
      {turnsCount > 0 && (
        <div className="rounded-lg border border-border bg-card overflow-hidden">
          <table className="w-full text-start border-collapse">
            <thead>
              <tr className="border-b border-border bg-muted/20">
                <th className="py-1.5 px-2 text-start text-[10px] font-medium text-muted-foreground w-8">#</th>
                <th className="py-1.5 px-2 text-start text-[10px] font-medium text-muted-foreground">{t("costDashboard.model", "Model")}</th>
                <th className="py-1.5 px-2 text-end text-[10px] font-medium text-muted-foreground">{t("costDashboard.tokens", "Tokens")}</th>
                <th className="py-1.5 px-2 text-end text-[10px] font-medium text-muted-foreground">{t("costDashboard.cost", "Cost")}</th>
                <th className="py-1.5 px-2 text-end text-[10px] font-medium text-muted-foreground">{t("costDashboard.duration", "Time")}</th>
              </tr>
            </thead>
            <tbody className="text-xs font-mono">
              {tokenMetrics!.turns.map((turn, i) => {
                const isLatest = i === turnsCount - 1;
                return (
                  <tr
                    key={i}
                    className={cn(
                      "border-b border-border/50 last:border-0 hover:bg-muted/10 transition-colors",
                      isLatest && "bg-primary/5 border-primary/20"
                    )}
                  >
                    <td className="py-1.5 px-2 text-muted-foreground">{i + 1}</td>
                    <td className="py-1.5 px-2 text-foreground font-medium truncate max-w-[200px]" title={turn.modelName ?? ""}>
                      {turn.modelName || "-"}
                    </td>
                    <td className="py-1.5 px-2 text-end text-[10px]">
                      <span className="text-muted-foreground">↑{turn.inputTokens}</span>{" "}
                      <span className="text-primary/80">↓{turn.outputTokens}</span>
                    </td>
                    <td className="py-1.5 px-2 text-end">{turn.cost > 0 ? fmtCost(turn.cost) : "-"}</td>
                    <td className="py-1.5 px-2 text-end text-muted-foreground">{formatDuration(turn.durationMs)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function StatCard({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-2 flex flex-col gap-1">
      <div className="flex items-center gap-1 text-[10px] text-muted-foreground font-medium">
        {icon}
        <span className="truncate">{label}</span>
      </div>
      <div className="text-sm font-mono font-semibold text-foreground truncate">
        {value}
      </div>
    </div>
  );
}

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
        const rawModel = (llm.model ?? llm.modelName ?? llm.modelId) as string | undefined;
        const rawProvider = (llm.provider ?? llm.engine) as string | undefined;
        if (rawModel && rawProvider) {
          model = rawModel.toLowerCase().includes(rawProvider.toLowerCase())
            ? rawModel
            : `${rawProvider} / ${rawModel}`;
        } else if (rawModel) {
          model = rawModel;
        } else if (rawProvider) {
          model = rawProvider;
        }
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
