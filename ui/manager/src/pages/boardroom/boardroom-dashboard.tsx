import { useState, useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Users, Plus, AlertCircle, RefreshCw, LayoutGrid, List } from "lucide-react";
import { cn } from "@/lib/utils";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { BoardroomCard } from "@/components/boardroom/boardroom-card";
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
    <div className="@container/br-dash">
      <div className="grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5">
        {Array.from({ length: 6 }).map((_, i) => (
          <Skeleton
            key={i}
            className="h-48 rounded-xl"
          />
        ))}
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
      <p className="text-sm text-muted-foreground">
        {t(
          "boardroom.dashboard.error",
          "Failed to load boardrooms. Please try again.",
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

const HERO_TEMPLATE_KEYS = ["advisory-board", "code-review", "pro-con"];

function DashboardEmpty() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const templates = getGroupTemplates(t);

  const heroTemplates = HERO_TEMPLATE_KEYS
    .map((key) => templates.find((tpl) => tpl.key === key))
    .filter(Boolean) as typeof templates;

  return (
    <div className="flex-1 flex items-center justify-center py-16">
      <div className="text-center max-w-lg space-y-6">
        {/* Decorative icon cluster */}
        <div className="flex items-center justify-center gap-3 mb-2">
          <span className="text-4xl">🎯</span>
          <span className="text-5xl">✦</span>
          <span className="text-4xl">💡</span>
        </div>

        <h2 className="text-2xl font-bold text-foreground">
          {t("boardroom.dashboard.welcomeTitle", "Your AI Advisory Board Awaits")}
        </h2>
        <p className="text-muted-foreground text-lg">
          {t(
            "boardroom.dashboard.welcomeDesc",
            "Assemble a team of AI advisors to help you brainstorm, debate, and make better decisions.",
          )}
        </p>

        {/* Template quick-start cards */}
        {heroTemplates.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-4">
            {heroTemplates.map((tpl) => (
              <Link
                key={tpl.key}
                to={`/boardroom/new?template=${tpl.key}`}
                className={cn(
                  "rounded-xl border p-4 text-start transition-all duration-150",
                  "border-slate-200 hover:border-indigo-400 hover:shadow-md hover:-translate-y-0.5",
                  "dark:border-slate-700 dark:hover:border-indigo-500",
                  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
                )}
              >
                <span className="text-2xl">{tpl.icon}</span>
                <h3 className="mt-2 text-sm font-semibold text-foreground line-clamp-1">
                  {tpl.name}
                </h3>
                <p className="mt-1 text-xs text-muted-foreground line-clamp-2">
                  {tpl.description}
                </p>
              </Link>
            ))}
          </div>
        )}

        <Button
          variant="primary"
          size="lg"
          onClick={() => navigate("/boardroom/new")}
          className="mt-4"
        >
          <Plus className="h-5 w-5" />
          {t("boardroom.dashboard.createFirst", "Create your first boardroom")}
        </Button>
      </div>
    </div>
  );
}

// ─── New Board Card (dashed) ─────────────────────────────────────

function NewBoardCard() {
  const { t } = useTranslation();

  return (
    <Link
      to="/boardroom/new"
      className={cn(
        "hidden @[32rem]/br-dash:flex",
        "flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed p-5 transition-all duration-150",
        "border-slate-300 text-slate-400 hover:border-indigo-400 hover:text-indigo-500 hover:bg-indigo-500/5",
        "dark:border-slate-700 dark:text-slate-500 dark:hover:border-indigo-500 dark:hover:text-indigo-400 dark:hover:bg-indigo-500/5",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
        "min-h-48",
      )}
    >
      <Plus className="h-8 w-8" />
      <span className="text-sm font-medium">
        {t("boardroom.dashboard.newBoardroom", "New Boardroom")}
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
        "bg-indigo-500 text-white shadow-lg",
        "hover:bg-indigo-600 active:scale-95",
        "transition-colors duration-150",
        "sm:hidden",
      )}
      style={{ animation: 'br-fab-in 200ms cubic-bezier(0.34,1.56,0.64,1) both' }}
      aria-label={t("boardroom.dashboard.newBoardroom", "New Boardroom")}
    >
      <Plus className="h-6 w-6" />
    </Link>
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
    <div className="inline-flex rounded-lg border border-slate-200 dark:border-slate-700" role="toolbar" aria-label={t("boardroom.dashboard.viewToggle", "View mode")}>
      <button
        type="button"
        onClick={() => onViewModeChange("grid")}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-s-lg ps-3 pe-3 py-1.5 text-sm font-medium transition-colors",
          viewMode === "grid"
            ? "bg-slate-100 text-foreground dark:bg-slate-800"
            : "text-muted-foreground hover:bg-slate-50 dark:hover:bg-slate-800/50",
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
            ? "bg-slate-100 text-foreground dark:bg-slate-800"
            : "text-muted-foreground hover:bg-slate-50 dark:hover:bg-slate-800/50",
        )}
        aria-label={t("boardroom.dashboard.listView", "List view")}
        aria-pressed={viewMode === "list"}
      >
        <List className="h-4 w-4" />
      </button>
    </div>
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
        <div className="mb-6">
          <Skeleton className="h-8 w-56" />
          <Skeleton className="mt-2 h-4 w-72" />
        </div>
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

  // Populated
  return (
    <div className="p-5 md:p-8">
      {/* Page header */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground md:text-3xl">
            {t("boardroom.dashboard.title", "Your Boardrooms")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "boardroom.dashboard.subtitle",
              "AI advisory boards for collaborative decision-making.",
            )}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <ViewToggle viewMode={viewMode} onViewModeChange={setViewMode} />
          <Button asChild variant="primary" size="sm" className="hidden sm:inline-flex">
            <Link to="/boardroom/new">
              <Plus className="h-4 w-4" />
              {t("boardroom.dashboard.newBoardroom", "New Boardroom")}
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

            {/* New board dashed card */}
            <NewBoardCard />
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

      {/* Mobile FAB (only visible below @container breakpoint) */}
      <MobileFab />
    </div>
  );
}

export { BoardroomDashboard };
