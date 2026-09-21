import { useState, useMemo, useCallback } from "react";
import type { AgentStat } from "@/hooks/use-workforce-analytics";
import { useTranslation } from "react-i18next";
import {
  MessageSquare,
  CheckCircle2,
  Users,
  Clock,
  AlertCircle,
} from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { useWorkforceAnalytics } from "@/hooks/use-workforce-analytics";
import { StatCard } from "@/components/workforce/analytics/stat-card";
import { ActivityHeatmap } from "@/components/workforce/analytics/activity-heatmap";
import { AgentLeaderboard } from "@/components/workforce/analytics/agent-leaderboard";
import { StyleBreakdown } from "@/components/workforce/analytics/style-breakdown";
import { OutcomeRing } from "@/components/workforce/analytics/outcome-ring";
import { PhaseBar } from "@/components/workforce/analytics/phase-bar";
import { TopDiscussions } from "@/components/workforce/analytics/top-discussions";
import { SkillCoverage } from "@/components/workforce/analytics/skill-coverage";
import {
  AnalyticsFilterBar,
} from "@/components/workforce/analytics/filter-bar";
import { AgentPerformanceSheet } from "@/components/workforce/agent-performance-sheet";
import { AgentEditorSheet } from "@/components/workforce/agent-editor-sheet";
import { AgentComparisonSheet } from "@/components/workforce/agent-comparison-sheet";
import {
  stateLabel,
  styleLabel,
  type ActiveFilter,
} from "@/components/workforce/analytics/filter-utils";
import { Link } from "react-router-dom";
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

/**
 * Page header with the route back to the Workforce dashboard.
 *
 * Rendered by every branch below, not just the populated one. It used to live
 * inline in the success return, so the loading, error and empty states — the
 * states a new deployment actually starts in — offered no way back at all. On
 * tablet the sidebar is behind a drawer, which left the user stuck.
 */
function AnalyticsHeader() {
  const { t } = useTranslation();
  return (
    <div
      className="flex items-center gap-3 br-section-enter"
      style={{ "--enter-delay": "0ms" } as React.CSSProperties}
    >
      <Link
        to="/workforce"
        className="flex h-8 w-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
        aria-label={t("Workforce.back", "Back")}
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
  );
}

function WorkforceAnalytics() {
  const { t } = useTranslation();

  const [filters, setFilters] = useState<FilterState>(INITIAL_FILTERS);
  const analytics = useWorkforceAnalytics(filters);

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

  // ── Agent sheets ───────────────────────────────────────────────
  const [selectedAgent, setSelectedAgent] = useState<AgentStat | null>(null);
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);
  const [comparisonPair, setComparisonPair] = useState<[AgentStat, AgentStat] | null>(null);
  const [compareFirstAgent, setCompareFirstAgent] = useState<AgentStat | null>(null);

  const handleAgentClick = useCallback(
    (agentId: string) => {
      const agent = analytics.agentStats.find((a) => a.agentId === agentId);
      if (!agent) return;

      // If we're in "pick second agent for comparison" mode
      if (compareFirstAgent) {
        if (agent.agentId !== compareFirstAgent.agentId) {
          setComparisonPair([compareFirstAgent, agent]);
        }
        setCompareFirstAgent(null);
        return;
      }

      setSelectedAgent(agent);
    },
    [analytics.agentStats, compareFirstAgent],
  );

  const handleCompare = useCallback(
    (agentId: string) => {
      const agent = analytics.agentStats.find((a) => a.agentId === agentId);
      if (!agent) return;
      setSelectedAgent(null);
      setCompareFirstAgent(agent);
    },
    [analytics.agentStats],
  );

  // ── Build active filter chips ─────────────────────────────────
  const activeFilters = useMemo<ActiveFilter[]>(() => {
    const list: ActiveFilter[] = [];
    if (filters.outcome) {
      list.push({
        type: "outcome",
        label: stateLabel(filters.outcome, t),
        value: filters.outcome,
      });
    }
    if (filters.style) {
      list.push({
        type: "style",
        label: styleLabel(filters.style, t),
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
    // `t` is a dependency now that the outcome chip is localized — without it
    // the chip keeps its old-language label until some other filter changes.
  }, [filters, t]);

  const hasActiveFilters = activeFilters.length > 0;

  // ── Loading skeleton ────────────────────────────────────────────
  if (analytics.isLoading) {
    return (
      <div className="p-6 space-y-6 max-w-7xl ms-auto me-auto">
        <AnalyticsHeader />

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
      <div className="flex h-full flex-col">
        <div className="w-full max-w-7xl ms-auto me-auto p-6 pb-0">
          <AnalyticsHeader />
        </div>
        <div className="flex flex-1 items-center justify-center p-8">
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
      </div>
    );
  }

  // ── Empty state ─────────────────────────────────────────────────
  if (analytics.totalDiscussions === 0) {
    return (
      <div className="flex h-full flex-col">
        <div className="w-full max-w-7xl ms-auto me-auto p-6 pb-0">
          <AnalyticsHeader />
        </div>
        <div className="flex flex-1 items-center justify-center p-8">
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
            to="/workforce/new"
            className="inline-flex items-center gap-2 rounded-lg bg-primary ps-4 pe-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
          >
            {t("analyticsPage.createFirst", "Assemble Task Force")}
          </Link>
        </div>
        </div>
      </div>
    );
  }

  // ── Main render ─────────────────────────────────────────────────
  return (
    <>
    {/* The Workforce shell clips its main area, so this page owns its own
        scroll box — otherwise everything below the fold is unreachable. The
        scroller spans the full width (scrollbar at the window edge) and the
        content stays centred inside it.
        A section, not a main: the shell already renders <main id="workforce-main">
        around the outlet, and nested main landmarks are invalid. */}
    <section
      className="flex-1 min-h-0 overflow-y-auto"
      aria-label={t("analyticsPage.title", "Insights")}
    >
    <div className="p-6 space-y-6 max-w-7xl ms-auto me-auto">
      <AnalyticsHeader />

      {/* Filter bar */}
      <AnalyticsFilterBar
        outcome={filters.outcome}
        style={filters.style}
        outcomeCounts={analytics.outcomeCounts}
        styleCounts={analytics.styleCounts}
        dateFilters={activeFilters.filter((f) => f.type === "date")}
        onOutcomeChange={setOutcome}
        onStyleChange={setStyle}
        onRemoveDateFilter={removeFilter}
        onClearAll={clearAll}
        hasActiveFilters={hasActiveFilters}
        totalResults={analytics.totalDiscussions}
        unfilteredTotal={analytics.unfilteredTotal}
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
        {compareFirstAgent && (
          <div className="mb-3 flex items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 ps-4 pe-2 py-2 animate-in fade-in-0 slide-in-from-top-2">
            <span className="text-sm text-primary font-medium flex-1">
              {t("analyticsPage.pickSecondAgent", "Click an agent to compare with {{name}}", { name: compareFirstAgent.displayName })}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setCompareFirstAgent(null)}
            >
              {t("analyticsPage.cancelCompare", "Cancel")}
            </Button>
          </div>
        )}
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
    </div>
    </section>

      {/* Agent Performance Sheet */}
      <AgentPerformanceSheet
        agent={selectedAgent}
        onClose={() => setSelectedAgent(null)}
        onEditAgent={(id) => {
          setSelectedAgent(null);
          setEditingAgentId(id);
        }}
        onCompare={handleCompare}
      />

      {/* Agent Editor Sheet */}
      <AgentEditorSheet
        agentId={editingAgentId}
        onClose={() => setEditingAgentId(null)}
      />

      {/* Agent Comparison Sheet */}
      <AgentComparisonSheet
        agents={comparisonPair}
        onClose={() => setComparisonPair(null)}
      />
    </>
  );
}

export { WorkforceAnalytics };
