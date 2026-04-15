import { useState, useMemo, useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Bot, Search, Plus, Upload, ExternalLink, Trash2, Copy, Download, ArrowUpDown, ArrowUp, ArrowDown } from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useInfiniteAgentDescriptors, useDeleteAgent, useDuplicateAgent, groupAgentsByName } from "@/hooks/use-agents";
import { AgentCard } from "@/components/agents/agent-card";
import { CreateAgentDialog } from "@/components/agents/create-agent-dialog";
import { ImportAgentDialog } from "@/components/agents/import-agent-dialog";
import { ExportAgentDialog } from "@/components/agents/export-agent-dialog";
import { CreateOrWizardDialog } from "@/components/shared/create-or-wizard-dialog";
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
import { useOnboarding } from "@/hooks/use-onboarding";

type SortField = "name" | "version" | "modified";
type SortDir = "asc" | "desc";

export function AgentsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [quickCreateOpen, setQuickCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [exportTarget, setExportTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("agents"));
  const [sortField, setSortField] = useState<SortField>("modified");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  // Auto-trigger agents onboarding chapter
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    const timer = setTimeout(() => maybeAutoStart("agents"), 500);
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
  } = useInfiniteAgentDescriptors(search);

  const deleteMutation = useDeleteAgent();
  const duplicateMutation = useDuplicateAgent();

  // Flatten infinite pages → single array → group by name → sort
  const groupedAgents = useMemo(() => {
    const flat = data?.pages.flat() ?? [];
    const grouped = groupAgentsByName(flat);
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

  function handleImportSuccess() {
    toast.success(t("agents.importSuccess", "Agent imported successfully"));
  }

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

  function handleDuplicate(id: string, version: number) {
    duplicateMutation.mutate(
      { id, version, deepCopy: true },
      {
        onSuccess: () => toast.success(t("agentDetail.duplicateSuccess")),
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode("agents", mode);
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Bot className="h-8 w-8 text-primary" />
            {t("pages.agents.title")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.agents.subtitle")}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            onClick={() => setImportOpen(true)}
            data-testid="import-agent-btn"
          >
            <Upload className="h-4 w-4" />
            {t("agents.import", "Import")}
          </Button>
          <Button
            onClick={() => setCreateOpen(true)}
            data-testid="create-agent-btn"
          >
            <Plus className="h-4 w-4" />
            {t("createOrWizard.newAgent", "New Agent")}
          </Button>
        </div>
      </div>

      {/* Search bar + View toggle */}
      <div className="flex items-center gap-3" data-tour="agents-search">
        <div className="relative flex-1">
          <Search className="absolute inset-s-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("common.search")}
            aria-label={t("common.search")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="agent-search"
          />
        </div>
        <ViewToggle view={view} onChange={handleViewChange} />
      </div>

      {/* Content */}
      <div data-tour="agents-content">
      {isLoading && (
        <div className="cq-card-grid">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="rounded-xl border border-border bg-card p-5 space-y-3">
              <Skeleton className="h-5 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-8 w-1/3" />
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

      {!isLoading && !isError && groupedAgents.length === 0 && (
        <EmptyState
          icon={Bot}
          title={search ? t("common.noResults") : t("agents.empty")}
          description={!search ? t("agents.emptyDescription", "Use the wizard to create a fully configured agent in minutes.") : undefined}
          actionLabel={!search ? t("agents.createAgent") : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      )}

      {!isLoading && !isError && groupedAgents.length > 0 && (
        <>
          {/* Results count */}
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("agents.count", { count: groupedAgents.length })}
            {hasNextPage && "+"}
          </p>

          {view === "card" ? (
            /* Card grid */
            <div
              className="cq-card-grid"
              data-testid="agent-grid"
            >
              {groupedAgents.map((agent) => (
                <AgentCard
                  key={agent.id}
                  agent={agent}
                  onDuplicate={handleDuplicate}
                  onDelete={handleDelete}
                  onExport={(id, version) => setExportTarget({ id, version })}
                />
              ))}
            </div>
          ) : (
            /* List table */
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="agent-list"
            >
              <table className="w-full">
                <thead>
                  <tr className="border-b border-border bg-secondary/50">
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground"
                      aria-sort={sortField === "name" ? (sortDir === "asc" ? "ascending" : "descending") : undefined}
                    >
                      <button
                        onClick={() => toggleSort("name")}
                        className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
                        aria-label={t("common.sortByName", "Sort by name")}
                      >
                        {t("common.name", "Name")}
                        {sortField === "name" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" aria-hidden="true" /> : <ArrowDown className="h-3 w-3" aria-hidden="true" />) : <ArrowUpDown className="h-3 w-3 opacity-30" aria-hidden="true" />}
                      </button>
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.id", "ID")}
                    </th>
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground"
                      aria-sort={sortField === "version" ? (sortDir === "asc" ? "ascending" : "descending") : undefined}
                    >
                      <button
                        onClick={() => toggleSort("version")}
                        className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
                        aria-label={t("common.sortByVersion", "Sort by version")}
                      >
                        {t("common.version", "Version")}
                        {sortField === "version" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" aria-hidden="true" /> : <ArrowDown className="h-3 w-3" aria-hidden="true" />) : <ArrowUpDown className="h-3 w-3 opacity-30" aria-hidden="true" />}
                      </button>
                    </th>
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground"
                      aria-sort={sortField === "modified" ? (sortDir === "asc" ? "ascending" : "descending") : undefined}
                    >
                      <button
                        onClick={() => toggleSort("modified")}
                        className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
                        aria-label={t("common.sortByModified", "Sort by last modified")}
                      >
                        {t("common.modified", "Modified")}
                        {sortField === "modified" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" aria-hidden="true" /> : <ArrowDown className="h-3 w-3" aria-hidden="true" />) : <ArrowUpDown className="h-3 w-3 opacity-30" aria-hidden="true" />}
                      </button>
                    </th>
                    <th className="px-5 py-3 text-end text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("conversations.actions", "Actions")}
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {groupedAgents.map((agent) => (
                    <tr
                      key={agent.id}
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/agentview/${agent.id}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          {agent.name || t("agents.unnamed", "Unnamed Agent")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {agent.id.slice(0, 12)}…
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                          v{agent.version}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {new Date(agent.lastModifiedOn).toLocaleString()}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={() => handleDuplicate(agent.id, agent.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                            aria-label={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" aria-hidden="true" />
                          </button>
                          <button
                            onClick={() => setExportTarget({ id: agent.id, version: agent.version })}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("agents.export", "Export")}
                            aria-label={t("agents.export", "Export")}
                          >
                            <Download className="h-4 w-4" aria-hidden="true" />
                          </button>
                          <button
                            onClick={() => handleDelete(agent.id, agent.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                            title={t("common.delete")}
                            aria-label={t("common.delete")}
                          >
                            <Trash2 className="h-4 w-4" aria-hidden="true" />
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

      {/* Create or Wizard dialog */}
      <CreateOrWizardDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        type="agent"
        wizardPath="/manage/agents/wizard"
        onQuickCreate={() => {
          setCreateOpen(false);
          setQuickCreateOpen(true);
        }}
      />

      {/* Quick Create dialog (standalone) */}
      <CreateAgentDialog open={quickCreateOpen} onClose={() => setQuickCreateOpen(false)} />

      {/* Import dialog */}
      <ImportAgentDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onSuccess={handleImportSuccess}
      />

      {/* Delete confirmation dialog */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("agents.confirmDelete")}
        description={t("agents.confirmDeleteDescription", "This action cannot be undone. The agent and all its data will be permanently removed.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />

      {/* Export dialog */}
      {exportTarget && (
        <ExportAgentDialog
          open
          onClose={() => setExportTarget(null)}
          agentId={exportTarget.id}
          agentVersion={exportTarget.version}
        />
      )}
    </div>
  );
}
