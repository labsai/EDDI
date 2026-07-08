import { useState, useEffect, useMemo } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Plus,
  AlertCircle,
  RefreshCw,
  LayoutGrid,
  List,
  Crosshair,
  Building2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { BoardroomCard } from "@/components/boardroom/boardroom-card";
import { AgentWorkforceCard, AddAgentCard } from "@/components/boardroom/agent-workforce-card";
import { KnowledgeHealthCard } from "@/components/boardroom/knowledge-health-card";
import { QuickActions } from "@/components/boardroom/quick-actions";
import { getGroupTemplates } from "@/lib/group-templates";

// ─── View mode persistence ───────────────────────────────────────

const VIEW_MODE_KEY = "boardroom-view-mode";

function getStoredViewMode(): "grid" | "list" {
  try {
    const stored = localStorage.getItem(VIEW_MODE_KEY);
    if (stored === "grid" || stored === "list") return stored;
  } catch {
    // localStorage may be unavailable
  }
  return "grid";
}

// ─── Loading Skeleton ────────────────────────────────────────────

function DashboardSkeleton() {
  return (
    <div className="space-y-8">
      {/* Workforce skeleton */}
      <div>
        <Skeleton className="h-5 w-40 mb-4" />
        <div className="flex gap-3 overflow-hidden">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-36 w-36 shrink-0 rounded-xl" />
          ))}
        </div>
      </div>

      {/* Health skeleton */}
      <Skeleton className="h-40 rounded-xl" />

      {/* Task forces skeleton */}
      <div>
        <Skeleton className="h-5 w-48 mb-4" />
        <div className="@container/br-dash">
          <div className="grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-48 rounded-xl" />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Error State ─────────────────────────────────────────────────

function DashboardError({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();

  return (
    <div className="flex flex-col items-center justify-center gap-4 py-24">
      <AlertCircle className="h-12 w-12 text-destructive/60" />
      <p className="text-sm text-muted-foreground text-center max-w-xs">
        {t(
          "boardroom.dashboard.loadError",
          "Couldn't load your workspace. Please try again.",
        )}
      </p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        <RefreshCw className="h-4 w-4" />
        {t("boardroom.dashboard.retry", "Retry")}
      </Button>
    </div>
  );
}

// ─── Welcome Hero (Empty State) ──────────────────────────────────

function DashboardEmpty() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const templates = getGroupTemplates(t);
  const heroTemplates = ["advisory-board", "task-force", "risk-assessment"]
    .map((key) => templates.find((tpl) => tpl.key === key))
    .filter(Boolean) as typeof templates;

  return (
    <div className="flex-1 flex items-center justify-center py-16">
      <div className="text-center max-w-lg space-y-6">
        {/* Decorative icon */}
        <div className="flex items-center justify-center gap-3 mb-2">
          <Building2 className="h-12 w-12 text-primary/60" />
        </div>

        <h2 className="text-2xl font-bold text-foreground">
          {t(
            "boardroom.dashboard.welcomeTitle",
            "Build Your Digital Workforce",
          )}
        </h2>
        <p className="text-muted-foreground text-lg">
          {t(
            "boardroom.dashboard.welcomeDesc",
            "Transform your team's expertise into permanent digital experts that collaborate, debate, and protect your organizational DNA.",
          )}
        </p>

        {/* Dual CTA */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-3 pt-4">
          <Button
            variant="primary"
            size="lg"
            onClick={() => navigate("/boardroom/new")}
            className="w-full sm:w-auto"
          >
            <Crosshair className="h-5 w-5" />
            {t(
              "boardroom.dashboard.assembleTaskForce",
              "Assemble Task Force",
            )}
          </Button>
          <Button
            variant="outline"
            size="lg"
            onClick={() => navigate("/boardroom/new")}
            className="w-full sm:w-auto"
          >
            <Plus className="h-5 w-5" />
            {t("boardroom.dashboard.deployAgent", "Deploy Agent")}
          </Button>
        </div>

        {/* Template quick-start cards */}
        {heroTemplates.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-6">
            {heroTemplates.map((tpl) => (
              <Link
                key={tpl.key}
                to={`/boardroom/new?template=${tpl.key}`}
                className={cn(
                  "rounded-xl border p-4 text-start transition-all duration-150",
                  "border-border hover:border-primary/70 hover:shadow-md hover:-translate-y-0.5",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                )}
              >
                <p className="text-2xl mb-1">{tpl.icon}</p>
                <p className="text-sm font-medium text-foreground">
                  {tpl.name}
                </p>
                <p className="mt-1 text-xs text-muted-foreground line-clamp-2">
                  {tpl.description}
                </p>
              </Link>
            ))}
          </div>
        )}

        {/* Pitch pillars */}
        <p className="text-xs text-muted-foreground/60 pt-4">
          {t(
            "boardroom.dashboard.pillars",
            "Business Continuity · Succession Planning · Collaborative Problem Solving",
          )}
        </p>
      </div>
    </div>
  );
}

// ─── View Toggle ─────────────────────────────────────────────────

function ViewToggle({
  viewMode,
  onViewModeChange,
}: {
  viewMode: "grid" | "list";
  onViewModeChange: (mode: "grid" | "list") => void;
}) {
  const { t } = useTranslation();

  return (
    <div className="inline-flex rounded-lg border border-border" role="toolbar" aria-label={t("boardroom.dashboard.viewToggle", "View mode")}>
      <button
        type="button"
        onClick={() => onViewModeChange("grid")}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-s-lg ps-3 pe-3 py-1.5 text-sm font-medium transition-colors",
          viewMode === "grid"
            ? "bg-muted text-foreground"
            : "text-muted-foreground hover:bg-muted/50",
        )}
        aria-label={t("boardroom.dashboard.gridView", "Grid view")}
        aria-pressed={viewMode === "grid"}
      >
        <LayoutGrid className="h-4 w-4" />
      </button>
      <button
        type="button"
        onClick={() => onViewModeChange("list")}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-e-lg ps-3 pe-3 py-1.5 text-sm font-medium transition-colors",
          viewMode === "list"
            ? "bg-muted text-foreground"
            : "text-muted-foreground hover:bg-muted/50",
        )}
        aria-label={t("boardroom.dashboard.listView", "List view")}
        aria-pressed={viewMode === "list"}
      >
        <List className="h-4 w-4" />
      </button>
    </div>
  );
}

// ─── New Task Force dashed card ──────────────────────────────────

function NewTaskForceCard() {
  const { t } = useTranslation();

  return (
    <Link
      className={cn(
        "hidden @[32rem]/br-dash:flex",
        "flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-5 transition-all duration-150",
        "border-border text-muted-foreground hover:border-primary/70 hover:text-primary hover:bg-primary/5",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        "min-h-48",
      )}
      to="/boardroom/new"
      aria-label={t("boardroom.dashboard.assembleTaskForce", "Assemble Task Force")}
    >
      <Plus className="h-8 w-8" />
      <span className="text-sm font-medium">
        {t("boardroom.dashboard.assembleTaskForce", "Assemble Task Force")}
      </span>
    </Link>
  );
}

// ─── Mobile FAB ──────────────────────────────────────────────────

function MobileFab() {
  const { t } = useTranslation();
  return (
    <Link
      to="/boardroom/new"
      className={cn(
        "fixed bottom-24 z-40",
        "end-5",
        "flex h-14 w-14 items-center justify-center rounded-full",
        "bg-primary text-primary-foreground shadow-lg",
        "hover:bg-primary/90 active:scale-95",
        "transition-colors duration-150",
        "sm:hidden",
      )}
      aria-label={t("boardroom.dashboard.assembleTaskForce", "Assemble Task Force")}
    >
      <Plus className="h-6 w-6" />
    </Link>
  );
}

// ─── Workforce Section ───────────────────────────────────────────

function WorkforceSection() {
  const { t } = useTranslation();
  const { data: agentsRaw } = useAgentDescriptors(50);
  const navigate = useNavigate();

  const agents = useMemo(
    () => (agentsRaw ? groupAgentsByName(agentsRaw).slice(0, 10) : []),
    [agentsRaw],
  );

  if (!agents.length) return null;

  return (
    <section aria-label={t("workforce.title", "Your Digital Workforce")}>
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-lg font-semibold text-foreground">
          {t("workforce.title", "Your Digital Workforce")}
        </h2>
        <span className="text-xs text-muted-foreground">
          {t("workforce.count", "{{count}} experts", {
            count: agents.length,
          })}
        </span>
      </div>

      <div className="flex gap-3 overflow-x-auto pb-2 -mx-1 px-1 scrollbar-thin">
        {agents.map((agent) => {
          const idMatch = agent.resource?.match(
            /\/agentstore\/agents\/([^?]+)/,
          );
          const agentId = idMatch?.[1] ?? agent.resource ?? "";

          return (
            <AgentWorkforceCard
              key={agent.resource}
              name={agent.name || t("workforce.unnamed", "Unnamed Agent")}
              agentId={agentId}
              description={agent.description}
              onClick={() => navigate("/boardroom/new")}
            />
          );
        })}
        <AddAgentCard onClick={() => navigate("/boardroom/new")} />
      </div>
    </section>
  );
}

// ─── Dashboard Page ──────────────────────────────────────────────

function BoardroomDashboard() {
  const { t } = useTranslation();
  const { data: boards, isLoading, isError, refetch } =
    useEnrichedGroupDescriptors();

  const [viewMode, setViewMode] = useState<"grid" | "list">(getStoredViewMode);

  useEffect(() => {
    try {
      localStorage.setItem(VIEW_MODE_KEY, viewMode);
    } catch {
      // localStorage may be unavailable
    }
  }, [viewMode]);

  // Loading
  if (isLoading) {
    return (
      <div className="p-5 md:p-8">
        <DashboardSkeleton />
      </div>
    );
  }

  // Error
  if (isError) {
    return (
      <div className="p-5 md:p-8">
        <DashboardError onRetry={() => refetch()} />
      </div>
    );
  }

  // Empty
  if (!boards || boards.length === 0) {
    return (
      <div className="p-5 md:p-8">
        <DashboardEmpty />
      </div>
    );
  }

  // Populated — 3-pillar Intelligence Dashboard
  return (
    <div className="p-5 md:p-8 space-y-8">
      {/* ─── Pillar 1: Your Digital Workforce ────────────────── */}
      <WorkforceSection />

      {/* ─── Knowledge Health ────────────────────────────────── */}
      <KnowledgeHealthCard />

      {/* ─── Pillar 2: Active Task Forces ────────────────────── */}
      <section aria-label={t("boardroom.dashboard.taskForcesLabel", "Active Task Forces")}>
        <div className="mb-4 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <h2 className="text-lg font-semibold text-foreground">
              {t("boardroom.dashboard.title", "Active Task Forces")}
            </h2>
            <p className="mt-0.5 text-sm text-muted-foreground">
              {t(
                "boardroom.dashboard.subtitle",
                "Collaborative agent groups solving complex challenges.",
              )}
            </p>
          </div>

          <div className="flex items-center gap-2">
            <ViewToggle viewMode={viewMode} onViewModeChange={setViewMode} />
            <Button asChild variant="primary" size="sm" className="hidden sm:inline-flex">
              <Link to="/boardroom/new">
                <Crosshair className="h-4 w-4" />
                {t("boardroom.dashboard.assembleTaskForce", "Assemble Task Force")}
              </Link>
            </Button>
          </div>
        </div>

        {/* Responsive card grid / list */}
        <div className="@container/br-dash">
          {viewMode === "grid" ? (
            <div className="grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5">
              {boards.map((board) => (
                <div key={board.id} className="group">
                  <BoardroomCard
                    id={board.id}
                    name={board.name}
                    description={board.description}
                    style={board.style}
                    members={board.members?.map((m) => ({
                      agentId: m.agentId,
                      displayName: m.displayName,
                      speakingOrder: null,
                      role: null,
                      memberType: m.memberType,
                    }))}
                    lastModified={board.lastModifiedOn ?? board.createdOn}
                    version={board.version}
                    viewMode="grid"
                  />
                </div>
              ))}

              {/* New task force dashed card */}
              <NewTaskForceCard />
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              {boards.map((board) => (
                <BoardroomCard
                  key={board.id}
                  id={board.id}
                  name={board.name}
                  description={board.description}
                  style={board.style}
                  members={board.members?.map((m) => ({
                    agentId: m.agentId,
                    displayName: m.displayName,
                    speakingOrder: null,
                    role: null,
                    memberType: m.memberType,
                  }))}
                  lastModified={board.lastModifiedOn ?? board.createdOn}
                  version={board.version}
                  viewMode="list"
                />
              ))}
            </div>
          )}
        </div>
      </section>

      {/* ─── Pillar 3: Quick Actions ─────────────────────────── */}
      <section aria-label={t("quickActions.title", "Quick Actions")}>
        <h2 className="text-lg font-semibold text-foreground mb-3">
          {t("quickActions.title", "Quick Actions")}
        </h2>
        <QuickActions />
      </section>

      {/* Mobile FAB */}
      <MobileFab />
    </div>
  );
}

export { BoardroomDashboard };
