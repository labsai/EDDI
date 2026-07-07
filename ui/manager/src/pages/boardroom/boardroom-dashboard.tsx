import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Users, Plus, AlertCircle, RefreshCw } from "lucide-react";
import { cn } from "@/lib/utils";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { BoardroomCard } from "@/components/boardroom/boardroom-card";



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

// ─── Empty State ─────────────────────────────────────────────────

function DashboardEmpty() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  return (
    <div className="flex flex-col items-center justify-center gap-5 py-24">
      <Users className="h-16 w-16 text-slate-300 dark:text-slate-600" />
      <div className="text-center">
        <h2 className="text-lg font-semibold text-foreground">
          {t(
            "boardroom.dashboard.createFirst",
            "Create your first boardroom",
          )}
        </h2>
        <p className="mt-1.5 max-w-sm text-sm text-muted-foreground">
          {t(
            "boardroom.dashboard.createFirstDesc",
            "Set up an AI advisory board to get diverse perspectives on any topic.",
          )}
        </p>
      </div>
      <Button
        variant="primary"
        size="lg"
        onClick={() => navigate("/boardroom/new")}
      >
        <Plus className="h-5 w-5" />
        {t("boardroom.dashboard.newBoardroom", "New Boardroom")}
      </Button>
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

// ─── Dashboard Page ──────────────────────────────────────────────

function BoardroomDashboard() {
  const { t } = useTranslation();
  const { data: boards, isLoading, isError, refetch } =
    useEnrichedGroupDescriptors();

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
      <div className="mb-6">
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

      {/* Responsive card grid */}
      <div className="@container/br-dash">
        <div className="grid grid-cols-1 @[32rem]/br-dash:grid-cols-2 @[56rem]/br-dash:grid-cols-3 gap-5">
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
            />
          ))}

          {/* New board dashed card */}
          <NewBoardCard />
        </div>
      </div>

      {/* Mobile FAB (only visible below @container breakpoint) */}
      <MobileFab />
    </div>
  );
}

export { BoardroomDashboard };
