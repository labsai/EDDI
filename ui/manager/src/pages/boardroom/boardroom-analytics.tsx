import { useState, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  MessageSquare,
  CheckCircle2,
  Users,
  Clock,
  AlertCircle,
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
import { SkillCoverage } from "@/components/boardroom/analytics/skill-coverage";
import {
  AnalyticsFilterBar,
} from "@/components/boardroom/analytics/filter-bar";
import {
  stateLabel,
  styleLabel,
  type ActiveFilter,
} from "@/components/boardroom/analytics/filter-utils";
import { Link, useNavigate } from "react-router-dom";
import { ChevronLeft } from "lucide-react";
import type { GroupConversationState, DiscussionStyle } from "@/lib/api/groups";

// ─── Helpers ─────────────────────────────────────────────────────

function formatDuration(ms: number): string {
  if (ms < 60_000) return `${Math.round(ms / 1000)}s`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
}

// ─── Filter state ────────────────────────────────────────────────

interface FilterState {
  outcome: GroupConversationState | null;
  style: DiscussionStyle | null;
  date: string | null;
}

const INITIAL_FILTERS: FilterState = {
  outcome: null,
  style: null,
  date: null,
};

// ─── Component ───────────────────────────────────────────────────

function BoardroomAnalytics() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [filters, setFilters] = useState<FilterState>(INITIAL_FILTERS);
  const analytics = useBoardroomAnalytics(filters);

  // ── Filter callbacks ──────────────────────────────────────────
  const setOutcome = useCallback(
    (state: GroupConversationState | null) =>
      setFilters((prev) => ({ ...prev, outcome: state })),
    [],
  );
  const setStyle = useCallback(
    (style: DiscussionStyle | null) =>
      setFilters((prev) => ({ ...prev, style: style })),
    [],
  );
  const setDate = useCallback(
    (date: string | null) =>
      setFilters((prev) => ({ ...prev, date: date })),
    [],
  );
  const clearAll = useCallback(() => setFilters(INITIAL_FILTERS), []);

  const removeFilter = useCallback((f: ActiveFilter) => {
    setFilters((prev) => ({ ...prev, [f.type]: null }));
  }, []);

  const handleAgentClick = useCallback(
    (agentId: string) => {
      // Navigate to the agent's thread in the boardroom
      void navigate(`/manage/agents?id=${agentId}`);
    },
    [navigate],
  );

  // ── Build active filter chips ─────────────────────────────────
  const activeFilters = useMemo<ActiveFilter[]>(() => {
    const list: ActiveFilter[] = [];
    if (filters.outcome) {
      list.push({
        type: "outcome",
        label: stateLabel(filters.outcome),
        value: filters.outcome,
      });
    }
    if (filters.style) {
      list.push({
        type: "style",
        label: styleLabel(filters.style),
        value: filters.style,
      });
    }
    if (filters.date) {
      list.push({
        type: "date",
        label: filters.date,
        value: filters.date,
      });
    }
    return list;
  }, [filters]);

  const hasActiveFilters = activeFilters.length > 0;

  // ── Loading skeleton ────────────────────────────────────────────
  if (analytics.isLoading) {
    return (
      <div className="p-6 space-y-6 max-w-7xl ms-auto me-auto">
        {/* Header skeleton */}
        <Skeleton className="h-8 w-48" />

        {/* KPI skeleton */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div
              key={i}
              className="rounded-xl border border-border bg-card p-5 space-y-2"
            >
              <Skeleton className="h-10 w-10 rounded-lg" />
              <Skeleton className="h-6 w-20" />
              <Skeleton className="h-4 w-32" />
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

  // ── Error state ─────────────────────────────────────────────────
  if (analytics.hasError && !analytics.isLoading) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="text-center max-w-sm br-section-enter">
          <div className="ms-auto me-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-destructive/10">
            <AlertCircle className="h-8 w-8 text-destructive" />
          </div>
          <p className="text-lg font-medium text-foreground mb-1">
            {t("analyticsPage.errorTitle", "Unable to load insights")}
          </p>
          <p className="text-sm text-muted-foreground mb-4">
            {t(
              "analyticsPage.errorDescription",
              "Some data could not be fetched. Please try again.",
            )}
          </p>
          <button
            onClick={() => window.location.reload()}
            className="inline-flex items-center gap-2 rounded-lg bg-primary ps-4 pe-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            {t("analyticsPage.retry", "Retry")}
          </button>
        </div>
      </div>
    );
  }

  // ── Empty state ─────────────────────────────────────────────────
  if (analytics.totalDiscussions === 0) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div className="text-center max-w-sm br-section-enter">
          <div className="ms-auto me-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-muted">
            <MessageSquare className="h-8 w-8 text-muted-foreground" />
          </div>
          <p className="text-lg font-medium text-foreground mb-1">
            {t("analyticsPage.emptyTitle", "No insights yet")}
          </p>
          <p className="text-sm text-muted-foreground mb-4">
            {analytics.groupCount > 0
              ? t(
                  "analyticsPage.emptyHaveGroups",
                  "You have {{count}} task forces but no discussions yet. Start a discussion to see insights.",
                  { count: analytics.groupCount },
                )
              : t(
                  "analyticsPage.emptyDescription",
                  "Start a task force discussion to see analytics here.",
                )}
          </p>
          <Link
            to="/boardroom/new"
            className="inline-flex items-center gap-2 rounded-lg bg-primary ps-4 pe-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            {t("analyticsPage.createFirst", "Assemble Task Force")}
          </Link>
        </div>
      </div>
    );
  }

  // ── Main render ─────────────────────────────────────────────────
  return (
    <main
      className="p-6 space-y-6 max-w-7xl ms-auto me-auto"
      aria-label={t("analyticsPage.title", "Insights")}
    >
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

      {/* Filter bar */}
      <AnalyticsFilterBar
        filters={activeFilters}
        outcome={filters.outcome}
        style={filters.style}
        onOutcomeChange={setOutcome}
        onStyleChange={setStyle}
        onRemove={removeFilter}
        onClearAll={clearAll}
      />

      {/* KPI row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          icon={MessageSquare}
          label={t("analyticsPage.totalDiscussions", "Discussions")}
          value={analytics.totalDiscussions}
          subtitle={
            analytics.isFiltered
              ? t("analyticsPage.filteredOf", "{{filtered}} of {{total}} total", {
                  filtered: analytics.totalDiscussions,
                  total: analytics.unfilteredTotal,
                })
              : t("analyticsPage.acrossGroups", "across {{count}} task forces", {
                  count: analytics.groupCount,
                })
          }
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

      {/* Activity heatmap — clickable days */}
      <div
        className="br-section-enter"
        style={{ "--enter-delay": "100ms" } as React.CSSProperties}
      >
        <ActivityHeatmap
          data={analytics.dailyActivity}
          selectedDate={filters.date}
          onSelectDate={setDate}
        />
      </div>

      {/* Two-column charts — clickable segments */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "150ms" } as React.CSSProperties}
        >
          <OutcomeRing
            data={analytics.outcomeDistribution}
            total={analytics.totalDiscussions}
            selected={filters.outcome}
            onSelect={setOutcome}
          />
        </div>
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "200ms" } as React.CSSProperties}
        >
          <StyleBreakdown
            data={analytics.styleDistribution}
            selected={filters.style}
            onSelect={setStyle}
          />
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

      {/* Agent leaderboard — clickable rows */}
      <div
        className="br-section-enter"
        style={{ "--enter-delay": "300ms" } as React.CSSProperties}
      >
        <AgentLeaderboard
          agents={analytics.agentStats}
          onAgentClick={handleAgentClick}
        />
      </div>

      {/* Skill coverage & filtered discussions */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "300ms" } as React.CSSProperties}
        >
          <SkillCoverage />
        </div>
        <div
          className="br-section-enter"
          style={{ "--enter-delay": "350ms" } as React.CSSProperties}
        >
          <TopDiscussions
            discussions={analytics.recentDiscussions}
            emptyMessage={
              hasActiveFilters
                ? t(
                    "analyticsPage.noMatchingDiscussions",
                    "No discussions match the active filters.",
                  )
                : undefined
            }
          />
        </div>
      </div>
    </main>
  );
}

export { BoardroomAnalytics };
