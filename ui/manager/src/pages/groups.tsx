import { useState, useEffect, useMemo, useCallback } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router-dom";
import { Boxes, Search, Plus, ExternalLink, Copy, Trash2, ArrowUp, ArrowDown, ArrowUpDown } from "lucide-react";
import { toast } from "sonner";
import { useEnrichedGroupDescriptors, useDeleteGroup, useDuplicateGroup } from "@/hooks/use-groups";
import { GroupCard } from "@/components/groups/group-card";
import { STYLE_INFO } from "@/lib/api/groups";
import { CreateGroupDialog } from "@/components/groups/create-group-dialog";
import { CreateOrWizardDialog } from "@/components/shared/create-or-wizard-dialog";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import {
  ViewToggle,
  type ViewMode,
} from "@/components/shared/view-toggle";
import { getStoredViewMode, setStoredViewMode } from "@/components/shared/view-mode";
import { formatRelativeTime } from "@/lib/utils";

type SortField = "name" | "style" | "members" | "modified";
type SortDir = "asc" | "desc";

const SORT_STORAGE_KEY = "eddi-groups-sort";

function getStoredSort(): { field: SortField; dir: SortDir } {
  try {
    const stored = localStorage.getItem(SORT_STORAGE_KEY);
    if (stored) {
      const parsed = JSON.parse(stored);
      if (parsed.field && parsed.dir) return parsed;
    }
  } catch { /* ignore */ }
  return { field: "modified", dir: "desc" };
}

function setStoredSort(field: SortField, dir: SortDir) {
  try {
    localStorage.setItem(SORT_STORAGE_KEY, JSON.stringify({ field, dir }));
  } catch { /* ignore */ }
}

export function GroupsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [quickCreateOpen, setQuickCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("groups"));
  const [sortField, setSortField] = useState<SortField>(() => getStoredSort().field);
  const [sortDir, setSortDir] = useState<SortDir>(() => getStoredSort().dir);

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("groups"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  const { data: enrichedGroups, isLoading, isError, refetch } = useEnrichedGroupDescriptors(100, 0, search);
  const deleteMutation = useDeleteGroup();
  const duplicateMutation = useDuplicateGroup();

  const groupedGroups = useMemo(() => {
    const list = enrichedGroups ?? [];
    return [...list].sort((a, b) => {
      let cmp = 0;
      if (sortField === "name") cmp = (a.name ?? "").localeCompare(b.name ?? "");
      else if (sortField === "style") cmp = (a.style ?? "").localeCompare(b.style ?? "");
      else if (sortField === "members") cmp = (a.members?.length ?? a.memberCount) - (b.members?.length ?? b.memberCount);
      else cmp = a.lastModifiedOn - b.lastModifiedOn;
      return sortDir === "asc" ? cmp : -cmp;
    });
  }, [enrichedGroups, sortField, sortDir]);

  const toggleSort = useCallback((field: SortField) => {
    if (sortField === field) {
      const newDir = sortDir === "asc" ? "desc" : "asc";
      setSortDir(newDir);
      setStoredSort(field, newDir);
    } else {
      const newDir = field === "modified" ? "desc" : "asc";
      setSortField(field);
      setSortDir(newDir);
      setStoredSort(field, newDir);
    }
  }, [sortField, sortDir]);

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
        onError: () => toast.error(t("common.error")),
      });
    }
  }

  function handleDuplicate(id: string, version: number) {
    duplicateMutation.mutate(
      { id, version },
      {
        onSuccess: () => toast.success(t("groups.duplicateSuccess", "Group duplicated")),
        onError: () => toast.error(t("common.error")),
      }
    );
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode("groups", mode);
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Boxes className="h-8 w-8 text-primary" />
            {t("pages.groups.title", "Groups")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.groups.subtitle", "Multi-agent discussion groups for structured debate and collaboration.")}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button onClick={() => setCreateOpen(true)} data-testid="create-group-btn">
            <Plus className="h-4 w-4" />
            {t("createOrWizard.newGroup", "New Group")}
          </Button>
        </div>
      </div>

      {/* Search bar + View toggle */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1">
          <Search className="absolute inset-s-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("common.search")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="group-search"
          />
        </div>
        <ViewToggle view={view} onChange={handleViewChange} />
      </div>

      {/* Loading */}
      <div data-tour="groups-content">
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

      {/* Error */}
      {isError && (
        <ErrorState message={t("common.error")} onRetry={() => refetch()} retryLabel={t("common.retry")} />
      )}

      {/* Empty */}
      {!isLoading && !isError && groupedGroups.length === 0 && (
        <EmptyState
          icon={Boxes}
          title={search ? t("common.noResults") : t("groups.empty", "No groups yet")}
          description={!search ? t("groups.emptyDescription", "Groups let multiple agents collaborate on structured discussions.") : undefined}
          actionLabel={!search ? t("groups.createGroup", "Create Group") : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      )}

      {/* Results */}
      {!isLoading && !isError && groupedGroups.length > 0 && (
        <>
          {/* Results count */}
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("groups.count", { count: groupedGroups.length, defaultValue: "{{count}} groups" })}
          </p>

          {view === "card" ? (
            /* Card grid */
            <div
              className="cq-card-grid"
              data-testid="group-grid"
            >
              {groupedGroups.map((group) => (
                <GroupCard
                  key={group.id}
                  group={group}
                  memberCount={group.memberCount}
                  members={group.members}
                  style={group.style}
                  onDuplicate={handleDuplicate}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          ) : (
            /* List table */
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="group-list"
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
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground"
                      aria-sort={sortField === "style" ? (sortDir === "asc" ? "ascending" : "descending") : undefined}
                    >
                      <button
                        onClick={() => toggleSort("style")}
                        className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
                        aria-label={t("groups.sortByStyle", "Sort by style")}
                      >
                        {t("groups.styleColumn", "Style")}
                        {sortField === "style" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" aria-hidden="true" /> : <ArrowDown className="h-3 w-3" aria-hidden="true" />) : <ArrowUpDown className="h-3 w-3 opacity-30" aria-hidden="true" />}
                      </button>
                    </th>
                    <th
                      className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground"
                      aria-sort={sortField === "members" ? (sortDir === "asc" ? "ascending" : "descending") : undefined}
                    >
                      <button
                        onClick={() => toggleSort("members")}
                        className="inline-flex items-center gap-1 hover:text-foreground transition-colors"
                        aria-label={t("groups.sortByMembers", "Sort by members")}
                      >
                        {t("groups.membersColumn", "Members")}
                        {sortField === "members" ? (sortDir === "asc" ? <ArrowUp className="h-3 w-3" aria-hidden="true" /> : <ArrowDown className="h-3 w-3" aria-hidden="true" />) : <ArrowUpDown className="h-3 w-3 opacity-30" aria-hidden="true" />}
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
                  {groupedGroups.map((group) => (
                    <tr
                      key={group.id}
                      className="hover:bg-secondary/30 transition-colors cursor-pointer"
                      onClick={() => navigate(`/manage/groups/${group.id}?version=${group.version}`)}
                      onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); navigate(`/manage/groups/${group.id}?version=${group.version}`); } }}
                      tabIndex={0}
                      role="link"
                      data-testid={`group-row-${group.id}`}
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/groups/${group.id}?version=${group.version}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                          onClick={(e) => e.stopPropagation()}
                        >
                          {group.name || t("groups.unnamed", "Unnamed Group")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        {(() => {
                          const info = group.style ? STYLE_INFO[group.style] : null;
                          return info ? (
                            <span
                              className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
                              aria-label={`${t("groups.styleColumn", "Style")}: ${info.label}`}
                              title={info.flow}
                              data-testid={`group-style-${group.id}`}
                            >
                              <span aria-hidden="true">{info.icon}</span>
                              {info.label}
                            </span>
                          ) : (
                            <span className="text-xs text-muted-foreground">—</span>
                          );
                        })()}
                      </td>
                      <td className="px-5 py-3">
                        <div
                          className="flex items-center gap-2"
                          title={group.members?.map(m => m.displayName).join(", ")}
                          role="list"
                          aria-label={t("groups.membersColumn", "Members")}
                          data-testid={`group-members-${group.id}`}
                        >
                          <div className="flex -space-x-1.5">
                            {(group.members ?? []).slice(0, 3).map((m, i) => (
                              <span
                                key={`${m.displayName}-${i}`}
                                className="flex h-6 w-6 items-center justify-center rounded-full bg-primary/10 text-[10px] font-bold text-primary ring-2 ring-card"
                                role="listitem"
                                aria-label={m.displayName}
                              >
                                {m.displayName.charAt(0).toUpperCase()}
                              </span>
                            ))}
                          </div>
                          <span className="text-xs text-muted-foreground">
                            {group.members?.length || group.memberCount}
                          </span>
                        </div>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground" title={new Date(group.lastModifiedOn).toLocaleString()}>
                          {formatRelativeTime(group.lastModifiedOn)}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={(e) => { e.stopPropagation(); handleDuplicate(group.id, group.version); }}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                            aria-label={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                          <button
                            onClick={(e) => { e.stopPropagation(); handleDelete(group.id, group.version); }}
                            className="rounded-md p-1.5 text-muted-foreground hover:text-destructive hover:bg-destructive/10 transition-colors"
                            title={t("common.delete")}
                            aria-label={t("common.delete")}
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
        </>
      )}
      </div>

      {/* Create or Wizard dialog */}
      <CreateOrWizardDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        type="group"
        wizardPath="/manage/groups/wizard"
        onQuickCreate={() => {
          setCreateOpen(false);
          setQuickCreateOpen(true);
        }}
      />

      {/* Quick Create dialog (standalone) */}
      <CreateGroupDialog
        open={quickCreateOpen}
        onClose={() => setQuickCreateOpen(false)}
      />

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("groups.confirmDelete", "Delete this group?")}
        description={t("groups.confirmDeleteDesc", "This will permanently delete the group configuration.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
