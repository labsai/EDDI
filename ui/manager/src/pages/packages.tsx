import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Package, Search, Plus, RefreshCw, AlertCircle } from "lucide-react";
import {
  usePackageDescriptors,
  useDeletePackage,
} from "@/hooks/use-packages";
import { PackageCard } from "@/components/packages/package-card";
import { CreatePackageDialog } from "@/components/packages/create-package-dialog";
import { parseResourceUri } from "@/lib/api/bots";
import { cn } from "@/lib/utils";
import type { BotDescriptor } from "@/lib/api/bots";

export function PackagesPage() {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [createOpen, setCreateOpen] = useState(false);

  const { data: packages, isLoading, isError, refetch } =
    usePackageDescriptors(100, 0, search);
  const deleteMutation = useDeletePackage();

  const enrichedPackages = (packages ?? []).map((pkg: BotDescriptor) => {
    const { id, version } = parseResourceUri(pkg.resource);
    return { ...pkg, id, version };
  });

  function handleDelete(id: string, version: number) {
    if (
      window.confirm(
        t("packages.confirmDelete", "Are you sure you want to delete this package?")
      )
    ) {
      deleteMutation.mutate({ id, version });
    }
  }

  function handleDuplicate(_id: string, _version: number) {
    // TODO: implement package duplicate when backend supports it
    void _id;
    void _version;
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
        <button
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md active:scale-[0.98]"
          data-testid="create-package-btn"
        >
          <Plus className="h-4 w-4" />
          {t("packages.createPackage", "Create Package")}
        </button>
      </div>

      {/* Search bar */}
      <div className="relative">
        <Search className="absolute inset-s-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t("common.search")}
          className="w-full rounded-lg border border-input bg-background py-2.5 ps-10 pe-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring transition-shadow"
          data-testid="package-search"
        />
      </div>

      {/* Content */}
      {isLoading && (
        <div className="flex items-center justify-center py-20">
          <RefreshCw className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {isError && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="mt-4 text-lg font-medium text-destructive">
            {t("common.error")}
          </p>
          <button
            onClick={() => refetch()}
            className="mt-4 rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
          >
            {t("common.retry")}
          </button>
        </div>
      )}

      {!isLoading && !isError && enrichedPackages.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
          <Package className="h-12 w-12 text-muted-foreground/50" />
          <p className="mt-4 text-lg font-medium text-muted-foreground">
            {search
              ? t("common.noResults")
              : t("packages.empty", "No packages yet. Create your first package!")}
          </p>
          {!search && (
            <button
              onClick={() => setCreateOpen(true)}
              className="mt-4 inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              <Plus className="h-4 w-4" />
              {t("packages.createPackage", "Create Package")}
            </button>
          )}
        </div>
      )}

      {!isLoading && !isError && enrichedPackages.length > 0 && (
        <>
          {/* Results count */}
          <p className="text-sm text-muted-foreground">
            {t("packages.count", {
              count: enrichedPackages.length,
              defaultValue: "{{count}} package(s)",
            })}
          </p>

          {/* Package grid */}
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
        </>
      )}

      {/* Create dialog */}
      <CreatePackageDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
      />
    </div>
  );
}
