import { useState, useEffect, useMemo, useCallback } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Plus,
  AlertCircle,
  RefreshCw,
  LayoutGrid,
  List,
  UsersRound,
  Star,
  CheckSquare,
  Square,
  Trash2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { useEnrichedGroupDescriptors, useDeleteGroup } from "@/hooks/use-groups";
import { useAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { BoardroomCard } from "@/components/boardroom/boardroom-card";
import { AgentWorkforceCard } from "@/components/boardroom/agent-workforce-card";
import { KnowledgeHealthCard } from "@/components/boardroom/knowledge-health-card";
import { QuickActions } from "@/components/boardroom/quick-actions";
import { OnboardingHero } from "@/components/boardroom/onboarding-hero";
import { usePinnedGroups } from "@/hooks/use-pinned-groups";
import { TemplatesPanel } from "@/components/boardroom/templates-panel";
import type { DiscussionTemplate } from "@/hooks/use-templates";
import { AgentEditorSheet } from "@/components/boardroom/agent-editor-sheet";
import { toast } from "sonner";

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

// (DashboardEmpty replaced by OnboardingHero component)

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
      to="/workforce/new"
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
      to="/workforce/new"
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
  const [editingAgentId, setEditingAgentId] = useState<string | null>(null);

  const agents = useMemo(
    () => (agentsRaw ? groupAgentsByName(agentsRaw).slice(0, 10) : []),
    [agentsRaw],
  );

  if (!agents.length) return null;

  return (
    <section
      aria-label={t("workforce.title", "Your Digital Workforce")}
      className="br-section-enter"
      style={{ '--enter-delay': '0ms' } as React.CSSProperties}
    >
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

      <div className="flex gap-3 overflow-x-auto pb-2 -ms-1 -me-1 ps-1 pe-1 scrollbar-thin">
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
              onClick={() => setEditingAgentId(agentId)}
            />
          );
        })}
      </div>
      <AgentEditorSheet agentId={editingAgentId} onClose={() => setEditingAgentId(null)} />
    </section>
  );
}

// ─── Dashboard Page ──────────────────────────────────────────────

function BoardroomDashboard() {
  const { t } = useTranslation();
  const { data: boards, isLoading, isError, refetch } =
    useEnrichedGroupDescriptors();
  const { pinned, togglePin, isPinned } = usePinnedGroups();
  const deleteGroup = useDeleteGroup();
  const navigate = useNavigate();

  const [viewMode, setViewMode] = useState<"grid" | "list">(getStoredViewMode);
  const [bulkMode, setBulkMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const pinnedBoards = useMemo(
    () => boards?.filter((b) => pinned.has(b.id)) ?? [],
    [boards, pinned],
  );
  const unpinnedBoards = useMemo(
    () => boards?.filter((b) => !pinned.has(b.id)) ?? [],
    [boards, pinned],
  );

  const toggleSelect = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const handleBulkDelete = useCallback(async () => {
    let successCount = 0;
    let failCount = 0;
    for (const id of selectedIds) {
      const board = boards?.find((b) => b.id === id);
      if (!board) continue;
      try {
        await deleteGroup.mutateAsync({ id, version: board.version ?? 1 });
        successCount++;
      } catch {
        failCount++;
      }
    }
    setSelectedIds(new Set());
    setBulkMode(false);
    if (failCount === 0) {
      toast.success(
        t("boardroom.dashboard.deletedCount", "Deleted {{count}} task forces", {
          count: successCount,
        }),
      );
    } else {
      toast.error(
        t("boardroom.dashboard.deletePartialFail", "{{success}} deleted, {{fail}} failed", {
          success: successCount,
          fail: failCount,
        }),
      );
    }
  }, [selectedIds, boards, deleteGroup, t]);

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
    return <OnboardingHero />;
  }

  // Populated — 3-pillar Intelligence Dashboard
  return (
    <div className="p-5 md:p-8 space-y-8">
      {/* ─── Pillar 1: Your Digital Workforce ────────────────── */}
      <WorkforceSection />

      {/* ─── Templates ────────────────────────────────────────── */}
      <TemplatesPanel
        onUseTemplate={(template: DiscussionTemplate) => {
          navigate(`/workforce/new?template=${template.id}`);
        }}
      />

      {/* ─── Pinned Task Forces ───────────────────────────────── */}
      {pinnedBoards.length > 0 && (
        <section
          aria-label={t("boardroom.dashboard.pinned", "Pinned")}
          className="br-section-enter"
          style={{ '--enter-delay': '50ms' } as React.CSSProperties}
        >
          <div className="flex items-center gap-2 mb-3">
            <Star className="h-4 w-4 text-primary fill-primary" />
            <h2 className="text-lg font-semibold text-foreground">
              {t("boardroom.dashboard.pinned", "Pinned")}
            </h2>
          </div>
          <div className="@container/br-dash">
            <div className={viewMode === "grid" ? "grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5" : "flex flex-col gap-2"}>
              {pinnedBoards.map((board) => (
                <div key={board.id} className="group relative">
                  {bulkMode && (
                    <button
                      type="button"
                      className="absolute top-2 start-2 z-10"
                      aria-label={selectedIds.has(board.id) ? t("boardroom.dashboard.deselect", "Deselect") : t("boardroom.dashboard.selectItem", "Select")}
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        toggleSelect(board.id);
                      }}
                    >
                      {selectedIds.has(board.id) ? (
                        <CheckSquare className="h-5 w-5 text-primary" />
                      ) : (
                        <Square className="h-5 w-5 text-muted-foreground" />
                      )}
                    </button>
                  )}
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
                    viewMode={viewMode}
                    isPinned={true}
                    onTogglePin={() => togglePin(board.id)}
                  />
                </div>
              ))}
            </div>
          </div>
        </section>
      )}

      {/* ─── Pillar 2: Active Task Forces ────────────────────── */}
      <section
        aria-label={t("boardroom.dashboard.taskForcesLabel", "Active Task Forces")}
        className="br-section-enter"
        style={{ '--enter-delay': '100ms' } as React.CSSProperties}
      >
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
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setBulkMode(!bulkMode);
                setSelectedIds(new Set());
              }}
            >
              <CheckSquare className="h-4 w-4" />
              {bulkMode
                ? t("boardroom.dashboard.cancel", "Cancel")
                : t("boardroom.dashboard.select", "Select")}
            </Button>
            <ViewToggle viewMode={viewMode} onViewModeChange={setViewMode} />
            <Button asChild variant="primary" size="sm" className="hidden sm:inline-flex">
              <Link to="/workforce/new">
                <UsersRound className="h-4 w-4" />
                {t("boardroom.dashboard.assembleTaskForce", "Assemble Task Force")}
              </Link>
            </Button>
          </div>
        </div>

        {/* Responsive card grid / list */}
        <div className="@container/br-dash">
          {viewMode === "grid" ? (
            <div className="grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5">
              {unpinnedBoards.map((board, index) => (
                <div
                  key={board.id}
                  className="group relative br-card-enter"
                  style={{ '--enter-delay': `${index * 60}ms` } as React.CSSProperties}
                >
                  {bulkMode && (
                    <button
                      type="button"
                      className="absolute top-2 start-2 z-10"
                      aria-label={selectedIds.has(board.id) ? t("boardroom.dashboard.deselect", "Deselect") : t("boardroom.dashboard.selectItem", "Select")}
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        toggleSelect(board.id);
                      }}
                    >
                      {selectedIds.has(board.id) ? (
                        <CheckSquare className="h-5 w-5 text-primary" />
                      ) : (
                        <Square className="h-5 w-5 text-muted-foreground" />
                      )}
                    </button>
                  )}
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
                    isPinned={isPinned(board.id)}
                    onTogglePin={() => togglePin(board.id)}
                  />
                </div>
              ))}

              {/* New task force dashed card */}
              <NewTaskForceCard />
            </div>
          ) : (
            <div className="flex flex-col gap-2">
              {unpinnedBoards.map((board) => (
                <div key={board.id} className="relative">
                  {bulkMode && (
                    <button
                      type="button"
                      className="absolute top-2 start-2 z-10"
                      aria-label={selectedIds.has(board.id) ? t("boardroom.dashboard.deselect", "Deselect") : t("boardroom.dashboard.selectItem", "Select")}
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        toggleSelect(board.id);
                      }}
                    >
                      {selectedIds.has(board.id) ? (
                        <CheckSquare className="h-5 w-5 text-primary" />
                      ) : (
                        <Square className="h-5 w-5 text-muted-foreground" />
                      )}
                    </button>
                  )}
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
                    viewMode="list"
                    isPinned={isPinned(board.id)}
                    onTogglePin={() => togglePin(board.id)}
                  />
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* ─── Bulk action floating bar ─────────────────────────── */}
      {bulkMode && selectedIds.size > 0 && (
        <div className="fixed bottom-6 inset-x-0 z-40 flex justify-center">
          <div className="bg-card border border-border rounded-full shadow-lg ps-4 pe-2 py-2 flex items-center gap-3 animate-in slide-in-from-bottom-4">
            <span className="text-sm font-medium">
              {t("boardroom.dashboard.selected", "{{count}} selected", {
                count: selectedIds.size,
              })}
            </span>
            <Button variant="destructive" size="sm" onClick={handleBulkDelete}>
              <Trash2 className="h-4 w-4" />
              {t("boardroom.dashboard.deleteSelected", "Delete")}
            </Button>
          </div>
        </div>
      )}

      {/* ─── Pillar 3: Quick Actions ─────────────────────────── */}
      <section
        aria-label={t("quickActions.title", "Quick Actions")}
        className="br-section-enter"
        style={{ '--enter-delay': '200ms' } as React.CSSProperties}
      >
        <h2 className="text-lg font-semibold text-foreground mb-3">
          {t("quickActions.title", "Quick Actions")}
        </h2>
        <QuickActions />
      </section>

      {/* ─── Knowledge Health ────────────────────────────────── */}
      <KnowledgeHealthCard />

      {/* Mobile FAB */}
      <MobileFab />
    </div>
  );
}

export { BoardroomDashboard };
