import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Search,
  Plus,
  FileCode,
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
  ExternalLink,
  Copy,
  Trash2,
} from "lucide-react";
import { getResourceType } from "@/lib/api/resources";
import { parseResourceUri } from "@/lib/api/agents";
import { useResourceDescriptors, useDeleteResource, useDuplicateResource } from "@/hooks/use-resources";
import { ResourceCard } from "@/components/resources/resource-card";
import { CreateResourceDialog } from "@/components/resources/create-resource-dialog";
import { cn } from "@/lib/utils";
import type { AgentDescriptor } from "@/lib/api/agents";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { BackLink } from "@/components/shared/back-link";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import {
  ViewToggle,
  getStoredViewMode,
  setStoredViewMode,
  type ViewMode,
} from "@/components/shared/view-toggle";

const ICON_MAP: Record<string, LucideIcon> = {
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
};

export function ResourceListPage() {
  const { type } = useParams<{ type: string }>();
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode(`resources-${type}`));

  const rt = getResourceType(type ?? "");

  const { data: items, isLoading, isError, refetch } = useResourceDescriptors(
    type ?? "",
    100,
    0,
    search
  );
  const deleteMutation = useDeleteResource(type ?? "");
  const duplicateMutation = useDuplicateResource(type ?? "");

  if (!rt) {
    return (
      <div className="space-y-4 py-20">
        <ErrorState message={t("resources.unknownType")} />
        <div className="text-center">
          <Link to="/manage/resources" className="text-sm text-primary hover:underline">
            {t("resources.backToResources")}
          </Link>
        </div>
      </div>
    );
  }

  const Icon = ICON_MAP[rt.icon] ?? FileCode;
  const typeName = t(`${rt.labelKey}.name`);

  const enrichedItems = (items ?? []).map((item: AgentDescriptor) => {
    const { id, version } = parseResourceUri(item.resource);
    return { ...item, id, version };
  });

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
        onSuccess: () => toast.success(t("common.duplicate") + " ✓"),
        onError: () => toast.error(t("common.error")),
      }
    );
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode(`resources-${type}`, mode);
  }

  return (
    <div className="space-y-6">
      {/* Back link */}
      <BackLink
        to="/manage/resources"
        label={t("resources.backToResources")}
      />

      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Icon className="h-8 w-8 text-primary" />
            {typeName}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t(`${rt.labelKey}.description`)}
          </p>
        </div>
        <Button
          onClick={() => setCreateOpen(true)}
          data-testid="create-resource-btn"
        >
          <Plus className="h-4 w-4" />
          {t("resources.create", { type: typeName })}
        </Button>
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
            data-testid="resource-search"
          />
        </div>
        <ViewToggle view={view} onChange={handleViewChange} />
      </div>

      {/* Content */}
      {isLoading && (
        <div
          className={cn(
            "grid gap-4",
            "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
          )}
        >
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

      {!isLoading && !isError && enrichedItems.length === 0 && (
        <EmptyState
          icon={Icon}
          title={search ? t("common.noResults") : t("resources.empty", { type: typeName })}
          description={!search ? t("resources.emptyDescription", { type: typeName, defaultValue: "Create your first {{type}} to use it in workflows." }) : undefined}
          actionLabel={!search ? t("resources.create", { type: typeName }) : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      )}

      {!isLoading && !isError && enrichedItems.length > 0 && (
        <>
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("resources.count", { count: enrichedItems.length })}
          </p>

          {view === "card" ? (
            <div
              className={cn(
                "grid gap-4",
                "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
              )}
              data-testid="resource-grid"
            >
              {enrichedItems.map((item) => (
                <ResourceCard
                  key={item.resource}
                  item={item}
                  typeSlug={type ?? ""}
                  iconName={rt.icon}
                  onDuplicate={handleDuplicate}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          ) : (
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="resource-list"
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
                  {enrichedItems.map((item) => (
                    <tr
                      key={item.resource}
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/resources/${type}/${item.id}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          {item.name || t("resources.unnamed", "Unnamed Resource")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {item.id.slice(0, 12)}…
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                          v{item.version}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {new Date(item.lastModifiedOn).toLocaleString()}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={() => handleDuplicate(item.id, item.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => handleDelete(item.id, item.version)}
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

      {/* Create dialog */}
      <CreateResourceDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        typeSlug={type ?? ""}
        typeName={typeName}
      />

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("resources.confirmDelete", { type: typeName })}
        description={t("resources.confirmDelete", { type: typeName })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
