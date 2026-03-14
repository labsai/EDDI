import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Package, Search, Plus, ExternalLink, Trash2, Copy } from "lucide-react";
import { toast } from "sonner";
import {
  usePackageDescriptors,
  useDeletePackage,
} from "@/hooks/use-packages";
import { PackageCard } from "@/components/packages/package-card";
import { CreatePackageDialog } from "@/components/packages/create-package-dialog";
import { parseResourceUri } from "@/lib/api/bots";
import { cn } from "@/lib/utils";
import type { BotDescriptor } from "@/lib/api/bots";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import {
  ViewToggle,
  getStoredViewMode,
  setStoredViewMode,
  type ViewMode,
} from "@/components/shared/view-toggle";
import { Link } from "react-router-dom";

export function PackagesPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; version: number } | null>(null);
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("packages"));

  const { data: packages, isLoading, isError, refetch } =
    usePackageDescriptors(100, 0, search);
  const deleteMutation = useDeletePackage();

  const enrichedPackages = (packages ?? []).map((pkg: BotDescriptor) => {
    const { id, version } = parseResourceUri(pkg.resource);
    return { ...pkg, id, version };
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

  function handleDuplicate(_id: string, _version: number) {
    // TODO: implement package duplicate when backend supports it
    void _id;
    void _version;
  }

  function handleViewChange(mode: ViewMode) {
    setView(mode);
    setStoredViewMode("packages", mode);
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Package className="h-8 w-8 text-primary" />
            {t("pages.packages.title")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.packages.subtitle")}
          </p>
        </div>
        <Button
          onClick={() => setCreateOpen(true)}
          data-testid="create-package-btn"
        >
          <Plus className="h-4 w-4" />
          {t("packages.createPackage")}
        </Button>
      </div>

      {/* Search bar + View toggle */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("common.search")}
            className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
            data-testid="package-search"
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

      {!isLoading && !isError && enrichedPackages.length === 0 && (
        <EmptyState
          icon={Package}
          title={search ? t("common.noResults") : t("packages.empty")}
          actionLabel={!search ? t("packages.createPackage") : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      )}

      {!isLoading && !isError && enrichedPackages.length > 0 && (
        <>
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("packages.count", { count: enrichedPackages.length })}
          </p>

          {view === "card" ? (
            <div
              className={cn(
                "grid gap-4",
                "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
              )}
              data-testid="package-grid"
            >
              {enrichedPackages.map((pkg) => (
                <PackageCard
                  key={pkg.resource}
                  pkg={pkg}
                  onDuplicate={handleDuplicate}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          ) : (
            <div
              className="overflow-hidden rounded-xl border bg-card shadow-sm"
              data-testid="package-list"
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
                  {enrichedPackages.map((pkg) => (
                    <tr
                      key={pkg.resource}
                      className="hover:bg-secondary/30 transition-colors"
                    >
                      <td className="px-5 py-3">
                        <Link
                          to={`/manage/packageview/${pkg.id}`}
                          className="text-sm font-medium text-foreground hover:text-primary transition-colors"
                        >
                          {pkg.name || t("packages.unnamed", "Unnamed Package")}
                          <ExternalLink className="ms-1 inline h-3 w-3 opacity-40" />
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <span className="font-mono text-xs text-muted-foreground">
                          {pkg.id.slice(0, 12)}…
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                          v{pkg.version}
                        </span>
                      </td>
                      <td className="px-5 py-3">
                        <span className="text-sm text-muted-foreground">
                          {new Date(pkg.lastModifiedOn).toLocaleString()}
                        </span>
                      </td>
                      <td className="px-5 py-3 text-end">
                        <div className="inline-flex items-center gap-1">
                          <button
                            onClick={() => handleDuplicate(pkg.id, pkg.version)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                            title={t("common.duplicate", "Duplicate")}
                          >
                            <Copy className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => handleDelete(pkg.id, pkg.version)}
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
      <CreatePackageDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
      />

      {/* Delete confirmation */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
        title={t("packages.confirmDelete")}
        description={t("packages.confirmDelete")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={confirmDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
