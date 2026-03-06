import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  Search,
  Plus,
  RefreshCw,
  AlertCircle,
  ArrowLeft,
  FileCode,
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
} from "lucide-react";
import { getResourceType } from "@/lib/api/resources";
import { parseResourceUri } from "@/lib/api/bots";
import { useResourceDescriptors, useDeleteResource, useDuplicateResource } from "@/hooks/use-resources";
import { ResourceCard } from "@/components/resources/resource-card";
import { CreateResourceDialog } from "@/components/resources/create-resource-dialog";
import { cn } from "@/lib/utils";
import type { BotDescriptor } from "@/lib/api/bots";
import type { LucideIcon } from "lucide-react";

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
      <div className="flex flex-col items-center justify-center py-20">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="mt-4 text-lg font-medium text-destructive">
          {t("resources.unknownType", "Unknown resource type")}
        </p>
        <Link
          to="/manage/resources"
          className="mt-4 text-sm text-primary hover:underline"
        >
          {t("resources.backToResources", "← Back to Resources")}
        </Link>
      </div>
    );
  }

  const Icon = ICON_MAP[rt.icon] ?? FileCode;
  const typeName = t(`${rt.labelKey}.name`);

  const enrichedItems = (items ?? []).map((item: BotDescriptor) => {
    const { id, version } = parseResourceUri(item.resource);
    return { ...item, id, version };
  });

  function handleDelete(id: string, version: number) {
    if (
      window.confirm(
        t("resources.confirmDelete", {
          type: typeName,
          defaultValue: `Are you sure you want to delete this ${typeName}?`,
        })
      )
    ) {
      deleteMutation.mutate({ id, version });
    }
  }

  function handleDuplicate(id: string, version: number) {
    duplicateMutation.mutate({ id, version });
  }

  return (
    <div className="space-y-6">
      {/* Back link */}
      <Link
        to="/manage/resources"
        className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
        data-testid="back-to-resources"
      >
        <ArrowLeft className="h-4 w-4" />
        {t("resources.backToResources", "Back to Resources")}
      </Link>

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
        <button
          onClick={() => setCreateOpen(true)}
          className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 hover:shadow-md active:scale-[0.98]"
          data-testid="create-resource-btn"
        >
          <Plus className="h-4 w-4" />
          {t("resources.create", { type: typeName, defaultValue: `Create ${typeName}` })}
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
          data-testid="resource-search"
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

      {!isLoading && !isError && enrichedItems.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
          <Icon className="h-12 w-12 text-muted-foreground/50" />
          <p className="mt-4 text-lg font-medium text-muted-foreground">
            {search
              ? t("common.noResults")
              : t("resources.empty", {
                  type: typeName,
                  defaultValue: `No ${typeName} resources yet.`,
                })}
          </p>
          {!search && (
            <button
              onClick={() => setCreateOpen(true)}
              className="mt-4 inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            >
              <Plus className="h-4 w-4" />
              {t("resources.create", { type: typeName, defaultValue: `Create ${typeName}` })}
            </button>
          )}
        </div>
      )}

      {!isLoading && !isError && enrichedItems.length > 0 && (
        <>
          {/* Results count */}
          <p className="text-sm text-muted-foreground">
            {t("resources.count", {
              count: enrichedItems.length,
              defaultValue: `${enrichedItems.length} resource(s)`,
            })}
          </p>

          {/* Resource grid */}
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
        </>
      )}

      {/* Create dialog */}
      <CreateResourceDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        typeSlug={type ?? ""}
        typeName={typeName}
      />
    </div>
  );
}
