import { useState, useEffect } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Boxes, Search, Plus, ExternalLink, Copy, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useEnrichedGroupDescriptors, useDeleteGroup, useDuplicateGroup } from "@/hooks/use-groups";
import { GroupCard } from "@/components/groups/group-card";
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

export function GroupsPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [quickCreateOpen, setQuickCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("groups"));

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("groups"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  const { data: enrichedGroups, isLoading, isError, refetch } = useEnrichedGroupDescriptors(100, 0, search);
  const deleteMutation = useDeleteGroup();
  const duplicateMutation = useDuplicateGroup();

  const groupedGroups = enrichedGroups ?? [];

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
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.name", "Name")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.id", "ID")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.version", "Version")}
                    </th>
                    <th className="px-5 py-3 text-start text-xs font-medium uppercase tracking-wider text-muted-foreground">
                      {t("common.modified", "Modified")}
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
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/groups/${group.id}?version=${group.version}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          {group.name || t("groups.unnamed", "Unnamed Group")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {group.id.slice(0, 12)}…
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                          v{group.version}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground" title={new Date(group.lastModifiedOn).toLocaleString()}>
                          {formatRelativeTime(group.lastModifiedOn)}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={() => handleDuplicate(group.id, group.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => handleDelete(group.id, group.version)}
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
