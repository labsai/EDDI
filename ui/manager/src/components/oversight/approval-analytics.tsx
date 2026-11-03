import { useTranslation } from "react-i18next";
import { BarChart3, TriangleAlert } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { useApprovalSummary } from "@/hooks/use-hitl-oversight";
import { duration, percent } from "@/components/oversight/format";
import { OVERSIGHT_LIMITS, type ApprovalSummary, type AutonomyTier, type OversightQuery } from "@/lib/api/hitl-oversight";

function Tile({ label, value, testId, hint }: { label: string; value: string; testId: string; hint?: string }) {
  return (
    <div className="rounded-xl border border-border bg-card p-4" data-testid={testId}>
      <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-foreground">{value}</p>
      {hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

/**
 * Approval analytics: KPI tiles over every decision in the window, and one row
 * per approval category with its outcome mix, top reason codes and tier.
 */
export function ApprovalAnalytics({ query, tiers }: { query: OversightQuery; tiers: Record<string, AutonomyTier> }) {
  const { t } = useTranslation();
  const overall = useApprovalSummary(query, "none");
  const byCategory = useApprovalSummary(query, "category");

  if (overall.isLoading || byCategory.isLoading) {
    return (
      <div className="cq-stat-grid" data-testid="oversight-analytics-loading">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-20 w-full" />
        ))}
      </div>
    );
  }
  if (overall.isError || byCategory.isError) {
    return <ErrorState message={t("common.error", "Something went wrong")} onRetry={() => void Promise.all([overall.refetch(), byCategory.refetch()])} retryLabel={t("common.retry", "Retry")} />;
  }
  const all = overall.data?.buckets[0];
  const categories = byCategory.data?.buckets ?? [];
  if (!all || (all.total === 0 && categories.length === 0)) {
    return (
      <EmptyState
        icon={BarChart3}
        title={t("oversight.analyticsEmptyTitle", "No decisions in this period")}
        description={t("oversight.analyticsEmptyDescription", "Approval decisions appear here once a human or a timeout decides a paused turn or tool call.")}
      />
    );
  }
  const samples = all.samples;
  const capacityShort = (samples?.expiredShare ?? 0) > OVERSIGHT_LIMITS.expiredShareWarning;

  return (
    <div className="space-y-4" data-testid="oversight-analytics">
      <div className="cq-stat-grid">
        <Tile label={t("oversight.kpiDecisions", "Decisions")} value={String(all.total)} testId="oversight-kpi-decisions" />
        <Tile label={t("oversight.kpiUnedited", "Approved unedited")} value={percent(all.uneditedApprovalRate)} testId="oversight-kpi-unedited" />
        <Tile label={t("oversight.kpiEdited", "Approved with edits")} value={percent(all.editRate)} testId="oversight-kpi-edited" />
        <Tile label={t("oversight.kpiRejected", "Rejected")} value={percent(all.rejectionRate)} testId="oversight-kpi-rejected" />
        <Tile
          label={t("oversight.kpiLatency", "Time to decide (p50 / p90)")}
          value={`${duration(all.latencyP50Ms)} / ${duration(all.latencyP90Ms)}`}
          testId="oversight-kpi-latency"
          hint={t("oversight.kpiLatencyHint", "Approximate")}
        />
        <Tile label={t("oversight.kpiTimeouts", "Decided by timeout")} value={percent(all.timeoutShare)} testId="oversight-kpi-timeouts" />
        <Tile label={t("oversight.kpiBacklog", "Samples awaiting review")} value={String(samples?.pending ?? 0)} testId="oversight-kpi-backlog" />
        <Tile label={t("oversight.kpiExpired", "Samples expired unreviewed")} value={percent(samples?.expiredShare)} testId="oversight-kpi-expired" />
      </div>
      {capacityShort && (
        <p className="flex items-center gap-2 rounded-lg bg-warning/10 px-3 py-2 text-sm text-warning" data-testid="oversight-capacity-warning">
          <TriangleAlert className="h-4 w-4" />
          {t("oversight.capacityWarning", "More than a fifth of the audit samples expire unreviewed — the review capacity is short.")}
        </p>
      )}
      {overall.data?.truncated && (
        <p className="text-xs text-muted-foreground" data-testid="oversight-truncated">
          {t("oversight.truncated", "This period holds more decisions than one summary reads; narrow it for exact numbers.")}
        </p>
      )}
      <div className="overflow-x-auto rounded-xl border border-border">
        <table className="w-full text-sm" data-testid="oversight-category-table">
          <thead className="bg-muted/50 text-start text-xs uppercase tracking-wider text-muted-foreground">
            <tr>
              <th className="px-3 py-2">{t("oversight.colCategory", "Category")}</th>
              <th className="px-3 py-2">{t("oversight.colTier", "Tier")}</th>
              <th className="px-3 py-2">{t("oversight.colDecisions", "Decisions")}</th>
              <th className="px-3 py-2">{t("oversight.colUnedited", "Unedited")}</th>
              <th className="px-3 py-2">{t("oversight.colRejected", "Rejected")}</th>
              <th className="px-3 py-2">{t("oversight.colReasons", "Top reasons")}</th>
              <th className="px-3 py-2">{t("oversight.colSamples", "Samples (flagged)")}</th>
            </tr>
          </thead>
          <tbody>
            {categories.map((row: ApprovalSummary) => (
              <tr key={row.key} className="border-t border-border" data-testid={`oversight-category-${row.key}`}>
                <td className="px-3 py-2 font-mono text-xs">
                  {row.key}
                  {row.legacy > 0 && (
                    <span className="ms-2 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">{t("oversight.legacy", "legacy")}</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  <TierBadge tier={tiers[row.key] ?? "GATED"} />
                </td>
                <td className="px-3 py-2">{row.total}</td>
                <td className="px-3 py-2">{percent(row.uneditedApprovalRate)}</td>
                <td className="px-3 py-2">{percent(row.rejectionRate)}</td>
                <td className="px-3 py-2 text-xs">{row.topReasonCodes.map((c) => `${c.code} (${c.count})`).join(", ") || "—"}</td>
                <td className="px-3 py-2">{row.samples ? `${row.samples.sampled} (${row.samples.flagged})` : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function TierBadge({ tier }: { tier: AutonomyTier }) {
  const { t } = useTranslation();
  const label: Record<AutonomyTier, string> = {
    GATED: t("oversight.tierGated", "Gated"),
    READY: t("oversight.tierReady", "Ready to promote"),
    PROMOTED: t("oversight.tierPromoted", "Promoted"),
    SUSPENDED: t("oversight.tierSuspended", "Suspended"),
  };
  const tone: Record<AutonomyTier, string> = {
    GATED: "bg-muted text-muted-foreground",
    READY: "bg-primary/10 text-primary",
    PROMOTED: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400",
    SUSPENDED: "bg-warning/10 text-warning",
  };
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tone[tier]}`} data-testid={`oversight-tier-${tier}`}>
      {label[tier]}
    </span>
  );
}
