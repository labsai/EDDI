import { useTranslation } from "react-i18next";
import {
  MessageSquare,
  CheckCircle2,
  Users,
  Clock,
} from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { useBoardroomAnalytics } from "@/hooks/use-boardroom-analytics";
import { StatCard } from "@/components/boardroom/analytics/stat-card";
import { ActivityHeatmap } from "@/components/boardroom/analytics/activity-heatmap";
import { AgentLeaderboard } from "@/components/boardroom/analytics/agent-leaderboard";
import { StyleBreakdown } from "@/components/boardroom/analytics/style-breakdown";
import { OutcomeRing } from "@/components/boardroom/analytics/outcome-ring";
import { PhaseBar } from "@/components/boardroom/analytics/phase-bar";
import { TopDiscussions } from "@/components/boardroom/analytics/top-discussions";
import { Link } from "react-router-dom";
import { ChevronLeft } from "lucide-react";

// ─── Helpers ─────────────────────────────────────────────────────

function formatDuration(ms: number): string {
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
}

// ─── Component ───────────────────────────────────────────────────

function BoardroomAnalytics() {
  const { t } = useTranslation();
  const analytics = useBoardroomAnalytics();

  // ── Loading skeleton ────────────────────────────────────────────
  if (analytics.isLoading) {
    return (
      <div className="p-6 space-y-6 max-w-7xl ms-auto me-auto">
        {/* Header skeleton */}
        <div className="flex items-center gap-3">
          <Skeleton className="h-8 w-8 rounded-lg" />
          <Skeleton className="h-7 w-48" />
        </div>

        {/* KPI row */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="rounded-xl border border-border bg-card p-5 space-y-3"
            >
              <Skeleton className="h-10 w-10 rounded-lg" />
              <Skeleton className="h-8 w-20" />
              <Skeleton className="h-3 w-32" />
            </div>
          ))}
        </div>

        {/* Charts skeleton */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <Skeleton className="h-64 rounded-xl" />
          <Skeleton className="h-64 rounded-xl" />
        </div>
        <Skeleton className="h-80 rounded-xl" />
      </div>
    );
  }

  // ── Empty state ─────────────────────────────────────────────────
  if (analytics.totalDiscussions === 0 && analytics.groupCount === 0) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="text-center max-w-sm br-section-enter">
          <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
            <MessageSquare className="h-8 w-8 text-muted-foreground" />
          </div>
          <p className="text-lg font-medium text-foreground mb-1">
            {t("analyticsPage.emptyTitle", "No insights yet")}
          </p>
          <p className="text-sm text-muted-foreground mb-4">
            {t(
              "analyticsPage.emptyDescription",
              "Start a task force discussion to see analytics here.",
            )}
          </p>
          <Link
            to="/boardroom/new"
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            {t("analyticsPage.createFirst", "Assemble Task Force")}
          </Link>
        </div>
      </div>
    );
  }

  // ── Main render ─────────────────────────────────────────────────
  return (
    <div className="p-6 space-y-6 max-w-7xl ms-auto me-auto">
      {/* Header */}
      <div
        className="flex items-center gap-3 br-section-enter"
        style={{ "--enter-delay": "0ms" } as React.CSSProperties}
      >
        <Link
          to="/boardroom"
          className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
          aria-label={t("boardroom.back", "Back")}
        >
          <ChevronLeft className="h-5 w-5" />
        </Link>
        <h1 className="text-xl font-semibold text-foreground">
          {t("analyticsPage.title", "Insights")}
        </h1>
        <span className="text-sm text-muted-foreground">
          {t("analyticsPage.subtitle", "Last 30 days")}
        </span>
      </div>

      {/* KPI row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          icon={MessageSquare}
          label={t("analyticsPage.totalDiscussions", "Discussions")}
          value={analytics.totalDiscussions}
          subtitle={t("analyticsPage.acrossGroups", "across {{count}} task forces", {
            count: analytics.groupCount,
          })}
          delay={0}
        />
        <StatCard
          icon={CheckCircle2}
          label={t("analyticsPage.completionRate", "Completion Rate")}
          value={`${analytics.completionRate}%`}
          subtitle={t("analyticsPage.sessionsCompleted", "sessions completed successfully")}
          delay={60}
        />
        <StatCard
          icon={Users}
          label={t("analyticsPage.activeExperts", "Active Experts")}
          value={`${analytics.activeExperts} / ${analytics.totalExperts}`}
          subtitle={t("analyticsPage.participatedRecently", "participated in discussions")}
          delay={120}
        />
        <StatCard
          icon={Clock}
          label={t("analyticsPage.avgDuration", "Avg Duration")}
          value={
            analytics.avgDurationMs > 0
              ? formatDuration(analytics.avgDurationMs)
              : "—"
          }
          subtitle={t("analyticsPage.perDiscussion", "per discussion")}
          delay={180}
        />
      </div>

      {/* Activity heatmap */}
      <div
        className="br-section-enter"
        style={{ "--enter-delay": "100ms" } as React.CSSProperties}
      >
        <ActivityHeatmap data={analytics.dailyActivity} />
      </div>

      {/* Two-column charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "150ms" } as React.CSSProperties}
        >
          <OutcomeRing
            data={analytics.outcomeDistribution}
            total={analytics.totalDiscussions}
          />
        </div>
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "200ms" } as React.CSSProperties}
        >
          <StyleBreakdown data={analytics.styleDistribution} />
        </div>
      </div>

      {/* Phase distribution */}
      {analytics.phaseDistribution.length > 0 && (
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "250ms" } as React.CSSProperties}
        >
          <PhaseBar data={analytics.phaseDistribution} />
        </div>
      )}

      {/* Agent leaderboard */}
      <div
        className="br-section-enter"
        style={{ "--enter-delay": "300ms" } as React.CSSProperties}
      >
        <AgentLeaderboard agents={analytics.agentStats} />
      </div>

      {/* Recent discussions */}
      <div
        className="br-section-enter"
        style={{ "--enter-delay": "350ms" } as React.CSSProperties}
      >
        <TopDiscussions discussions={analytics.recentDiscussions} />
      </div>
    </div>
  );
}

export { BoardroomAnalytics };
