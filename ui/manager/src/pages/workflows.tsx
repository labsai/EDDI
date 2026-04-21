import { useState, useMemo, useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Workflow, Search, Plus, ExternalLink, Trash2, Copy, ArrowUpDown, ArrowUp, ArrowDown } from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import {
  useInfiniteWorkflowDescriptors,
  useDeleteWorkflow,
  groupWorkflowsByName,
} from "@/hooks/use-workflows";
import { WorkflowCard } from "@/components/workflows/workflow-card";
import { CreateWorkflowDialog } from "@/components/workflows/create-workflow-dialog";
import { useOnboarding } from "@/hooks/use-onboarding";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { InfiniteScrollSentinel } from "@/components/shared/infinite-scroll-sentinel";
import {
  ViewToggle,
  type ViewMode,
} from "@/components/shared/view-toggle";
import { getStoredViewMode, setStoredViewMode } from "@/components/shared/view-mode";
import { Link } from "react-router-dom";

type SortField = "name" | "version" | "modified";
type SortDir = "asc" | "desc";

export function WorkflowsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("workflows"));
  const [sortField, setSortField] = useState<SortField>("modified");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  // Auto-trigger workflows onboarding chapter
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    const timer = setTimeout(() => maybeAutoStart("workflows"), 500);
    return () => clearTimeout(timer);
  }, [maybeAutoStart]);

  const {
    data,
    isLoading,
    isError,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteWorkflowDescriptors(search);

  const deleteMutation = useDeleteWorkflow();

  // Flatten infinite pages → group by name → sort
  const enrichedWorkflows = useMemo(() => {
    const flat = data?.pages.flat() ?? [];
    const grouped = groupWorkflowsByName(flat);
    return [...grouped].sort((a, b) => {
      let cmp = 0;
      if (sortField === "name") cmp = (a.name ?? "").localeCompare(b.name ?? "");
      else if (sortField === "version") cmp = a.version - b.version;
      else cmp = new Date(a.lastModifiedOn).getTime() - new Date(b.lastModifiedOn).getTime();
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [data, sortField, sortDir]);

  const toggleSort = useCallback((field: SortField) => {
    if (sortField === field) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortField(field);
      setSortDir(field === "modified" ? "desc" : "asc");
    }
  }, [sortField]);

  function handleDelete(id: string, version: number) {
    setDeleteTarget({ id, version });
  }

  function confirmDelete() {
    if (deleteTarget) {
      deleteMutation.mutate(deleteTarget, {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          setDeleteTarget(null);
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      });
    }
  }

  function handleDuplicate(_id: string, _version: number) {
    // TODO: implement workflow duplicate when backend supports it
    void _id;
    void _version;
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode("workflows", mode);
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Workflow className="h-8 w-8 text-primary" />
            {t("pages.packages.title")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.packages.subtitle")}
          </p>
        </div>
        <Button
          onClick={() => setCreateOpen(true)}
          data-testid="create-workflow-btn"
        >
          <Plus className="h-4 w-4" />
          {t("packages.createWorkflow")}
        </Button>
      </div>

      {/* Search bar + View toggle */}
      <div className="flex items-center gap-3" data-tour="workflows-search">
        <div className="relative flex-1">
          <Search className="absolute inset-s-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("common.search")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="workflow-search"
          />
        </div>
        <ViewToggle view={view} onChange={handleViewChange} />
      </div>

      {/* Content */}
      <div data-tour="workflows-content">
      {isLoading && (
        <div className="cq-card-grid">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="rounded-xl border border-border bg-card p-5 space-y-3">
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-4 w-full" />
            </div>
          ))}
        </div>
      )}

      {isError && (
        <ErrorState
          message={t("common.error")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry")}
        />
      )}

      {!isLoading && !isError && enrichedWorkflows.length === 0 && (
        <EmptyState
          icon={Workflow}
          title={search ? t("common.noResults") : t("packages.empty")}
          description={!search ? t("packages.emptyDescription", "Workflows define the processing pipeline for your agents.") : undefined}
          actionLabel={!search ? t("packages.createWorkflow") : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      )}

      {!isLoading && !isError && enrichedWorkflows.length > 0 && (
        <>
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("packages.count", { count: enrichedWorkflows.length })}
            {hasNextPage && "+"}
          </p>

          {view === "card" ? (
            <div
              className="cq-card-grid"
              data-testid="workflow-grid"
            >
              {enrichedWorkflows.map((wf) => (
                <WorkflowCard
                  key={wf.resource}
                  workflow={wf}
                  onDuplicate={handleDuplicate}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          ) : (
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="workflow-list"
            >
              <table className="w-full">
                <thead>
                  <tr className="border-b border-border bg-secondary/50">
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort("name")}
                    >
                      <span className="inline-flex items-center gap-1">
                        {t("common.name", "Name")}
                        {sortField === "name" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />) : <ArrowUpDown className="h-3 w-3 opacity-30" />}
                      </span>
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.id", "ID")}
                    </th>
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort("version")}
                    >
                      <span className="inline-flex items-center gap-1">
                        {t("common.version", "Version")}
                        {sortField === "version" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />) : <ArrowUpDown className="h-3 w-3 opacity-30" />}
                      </span>
                    </th>
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground cursor-pointer select-none hover:text-foreground transition-colors"
                      onClick={() => toggleSort("modified")}
                    >
                      <span className="inline-flex items-center gap-1">
                        {t("common.modified", "Modified")}
                        {sortField === "modified" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />) : <ArrowUpDown className="h-3 w-3 opacity-30" />}
                      </span>
                    </th>
                    <th className="px-5 py-3 text-end text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.actions", "Actions")}
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {enrichedWorkflows.map((wf) => (
                    <tr
                      key={wf.resource}
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/workflowview/${wf.id}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          {wf.name || t("packages.unnamed", "Unnamed Workflow")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {wf.id.slice(0, 12)}…
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                          v{wf.version}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {new Date(wf.lastModifiedOn).toLocaleString()}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={() => handleDuplicate(wf.id, wf.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => handleDelete(wf.id, wf.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                            title={t("common.delete")}
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Infinite scroll sentinel */}
          <InfiniteScrollSentinel
            onLoadMore={() => fetchNextPage()}
            isFetchingMore={isFetchingNextPage}
            hasMore={!!hasNextPage}
          />
        </>
      )}
      </div>

      {/* Create dialog */}
      <CreateWorkflowDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
      />

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("packages.confirmDelete")}
        description={t("packages.confirmDeleteDescription", "This action cannot be undone. The workflow and all its data will be permanently removed.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
