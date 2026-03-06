import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ArrowLeft,
  RefreshCw,
  AlertCircle,
  FileCode,
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
  Trash2,
  Copy,
} from "lucide-react";
import { getResourceType } from "@/lib/api/resources";
import { useResource, useDeleteResource, useDuplicateResource } from "@/hooks/use-resources";
import { cn } from "@/lib/utils";
import { useNavigate } from "react-router-dom";
import type { LucideIcon } from "lucide-react";

const ICON_MAP: Record<string, LucideIcon> = {
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
};

export function ResourceDetailPage() {
  const { type, id } = useParams<{ type: string; id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const rt = getResourceType(type ?? "");
  const Icon = ICON_MAP[rt?.icon ?? ""] ?? FileCode;
  const typeName = rt ? t(`${rt.labelKey}.name`) : type ?? "";

  // Always default to version 1 for now
  const { data, isLoading, isError, refetch } = useResource(
    type ?? "",
    id ?? "",
    1
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

  function handleDelete() {
    if (
      window.confirm(
        t("resources.confirmDelete", {
          type: typeName,
          defaultValue: `Are you sure you want to delete this ${typeName}?`,
        })
      )
    ) {
      deleteMutation.mutate(
        { id: id ?? "", version: 1 },
        { onSuccess: () => navigate(`/manage/resources/${type}`) }
      );
    }
  }

  function handleDuplicate() {
    duplicateMutation.mutate(
      { id: id ?? "", version: 1 },
      {
        onSuccess: (result) => {
          const parts = result.location.split("/");
          const newId = (parts[parts.length - 1] ?? "").split("?")[0];
          if (newId) {
            navigate(`/manage/resources/${type}/${newId}`);
          }
        },
      }
    );
  }

  return (
    <div className="space-y-6">
      {/* Back link */}
      <Link
        to={`/manage/resources/${type}`}
        className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
        data-testid="back-to-list"
      >
        <ArrowLeft className="h-4 w-4" />
        {t("resources.backToList", {
          type: typeName,
          defaultValue: `Back to ${typeName}`,
        })}
      </Link>

      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Icon className="h-8 w-8 text-primary" />
            {typeName}
          </h1>
          <p className="mt-1 font-mono text-xs text-muted-foreground">{id}</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleDuplicate}
            disabled={duplicateMutation.isPending}
            className="inline-flex items-center gap-2 rounded-lg border border-input px-4 py-2.5 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary active:scale-[0.98] disabled:opacity-50"
          >
            <Copy className="h-4 w-4" />
            {t("common.duplicate")}
          </button>
          <button
            onClick={handleDelete}
            disabled={deleteMutation.isPending}
            className="inline-flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-2.5 text-sm font-medium text-destructive shadow-sm transition-all hover:bg-destructive/20 active:scale-[0.98] disabled:opacity-50"
          >
            <Trash2 className="h-4 w-4" />
            {t("common.delete")}
          </button>
        </div>
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

      {!isLoading && !isError && data !== undefined && (
        <div className="rounded-xl border bg-card shadow-sm">
          <div className="border-b border-border px-6 py-4">
            <h2 className="text-lg font-semibold text-foreground">
              {t("resources.rawConfig", "Raw Configuration")}
            </h2>
            <p className="text-sm text-muted-foreground">
              {t("resources.rawConfigHint", "JSON configuration for this resource")}
            </p>
          </div>
          <div className="p-6">
            <pre
              className={cn(
                "overflow-x-auto rounded-lg bg-muted/50 p-4 text-sm text-foreground",
                "font-mono leading-relaxed"
              )}
            >
              {JSON.stringify(data, null, 2)}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}
