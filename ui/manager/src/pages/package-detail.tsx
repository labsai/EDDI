import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Package,
  Settings,
  Trash2,
  RefreshCw,
  AlertCircle,
  ChevronDown,
  ChevronUp,
  Puzzle,
} from "lucide-react";
import { usePackage, useUpdatePackage, useDeletePackage } from "@/hooks/use-packages";
import type { PackageExtension } from "@/lib/api/packages";

/** Map extension type IDs to human-readable labels */
const extensionTypeLabels: Record<string, string> = {
  "ai.labs.parser": "Parser",
  "ai.labs.behavior": "Behavior Rules",
  "ai.labs.property": "Property Setter",
  "ai.labs.httpcalls": "HTTP Calls",
  "ai.labs.langchain": "LangChain",
  "ai.labs.output": "Output",
  "ai.labs.output.template": "Output Template",
};

function getExtensionLabel(type: string): string {
  return extensionTypeLabels[type] || type;
}

export function PackageDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [version] = useState(1); // TODO: version picker

  const { data: pkg, isLoading, isError, refetch } = usePackage(id!, version);
  const updateMutation = useUpdatePackage();
  const deleteMutation = useDeletePackage();

  function handleRemoveExtension(index: number) {
    if (!pkg) return;
    const updated = pkg.packageExtensions.filter((_, i) => i !== index);
    updateMutation.mutate({
      id: id!,
      version,
      config: { packageExtensions: updated },
    });
  }

  async function handleDelete() {
    if (
      window.confirm(
        t("packages.confirmDelete", "Are you sure you want to delete this package?")
      )
    ) {
      await deleteMutation.mutateAsync({ id: id!, version });
      navigate("/manage/packages");
    }
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20">
        <RefreshCw className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (isError || !pkg) {
    return (
      <div className="space-y-4">
        <BackLink />
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="mt-4 text-lg font-medium text-destructive">
            {t("common.error")}
          </p>
          <button
            onClick={() => refetch()}
            className="mt-4 rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20"
          >
            {t("common.retry")}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-2">
          <BackLink />
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Package className="h-8 w-8 text-primary" />
            {t("packageDetail.title", "Package Detail")}
          </h1>
          <p className="font-mono text-sm text-muted-foreground">ID: {id}</p>
        </div>

        {/* Actions */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-full bg-primary/10 px-3 py-1.5 text-sm font-medium text-primary">
            v{version}
          </span>
          <button
            onClick={handleDelete}
            className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Extensions section */}
      <section className="rounded-xl border bg-card shadow-sm">
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            <Puzzle className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">
              {t("packageDetail.extensions", "Extensions")}
            </h2>
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              {pkg.packageExtensions?.length ?? 0}
            </span>
          </div>
        </div>

        {/* Extension list */}
        <div className="divide-y divide-border">
          {(!pkg.packageExtensions || pkg.packageExtensions.length === 0) && (
            <div className="flex flex-col items-center justify-center py-12 text-muted-foreground">
              <Puzzle className="h-10 w-10 opacity-50" />
              <p className="mt-3 text-sm">
                {t(
                  "packageDetail.noExtensions",
                  "No extensions in this package"
                )}
              </p>
            </div>
          )}

          {pkg.packageExtensions?.map((ext, index) => (
            <ExtensionRow
              key={index}
              extension={ext}
              onRemove={() => handleRemoveExtension(index)}
              isRemoving={updateMutation.isPending}
            />
          ))}
        </div>
      </section>

      {/* Raw config (collapsible) */}
      <RawConfigSection config={pkg} />
    </div>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link
      to="/manage/packages"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {t("packageDetail.backToPackages", "Back to Packages")}
    </Link>
  );
}

function ExtensionRow({
  extension,
  onRemove,
  isRemoving,
}: {
  extension: PackageExtension;
  onRemove: () => void;
  isRemoving: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const configUri = extension.config?.["uri"] as string | undefined;
  const parsedUri = configUri ? parseConfigUri(configUri) : null;

  return (
    <div className="px-5 py-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3 min-w-0">
          <button
            onClick={() => setExpanded(!expanded)}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
          >
            {expanded ? (
              <ChevronUp className="h-4 w-4" />
            ) : (
              <ChevronDown className="h-4 w-4" />
            )}
          </button>
          <Settings className="h-4 w-4 shrink-0 text-muted-foreground" />
          <div className="min-w-0">
            <p className="text-sm font-medium text-foreground">
              {getExtensionLabel(extension.type)}
            </p>
            {parsedUri && (
              <p className="text-xs text-muted-foreground truncate">
                {parsedUri}
              </p>
            )}
          </div>
        </div>
        <button
          onClick={onRemove}
          disabled={isRemoving}
          className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors disabled:opacity-50"
          title="Remove extension"
        >
          <Trash2 className="h-4 w-4" />
        </button>
      </div>

      {/* Expanded config view */}
      {expanded && (
        <div className="mt-3 ms-10 rounded-lg bg-secondary p-3">
          <pre className="overflow-x-auto text-xs text-foreground">
            {JSON.stringify(extension, null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
}

function RawConfigSection({
  config,
}: {
  config: { packageExtensions?: PackageExtension[] };
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  return (
    <section className="rounded-xl border bg-card shadow-sm">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between p-5 text-start"
      >
        <div className="flex items-center gap-2">
          <Settings className="h-5 w-5 text-muted-foreground" />
          <h2 className="text-lg font-semibold text-foreground">
            {t("packageDetail.rawConfig", "Raw Configuration")}
          </h2>
        </div>
        <span className="text-sm text-muted-foreground">
          {expanded ? "▲" : "▼"}
        </span>
      </button>
      {expanded && (
        <div className="border-t border-border p-5">
          <pre className="overflow-x-auto rounded-lg bg-secondary p-4 text-sm text-foreground">
            {JSON.stringify(config, null, 2)}
          </pre>
        </div>
      )}
    </section>
  );
}

function parseConfigUri(uri: string): string | null {
  try {
    const url = new URL(uri.replace("eddi://", "http://"));
    const parts = url.pathname.split("/");
    return parts[parts.length - 1] || null;
  } catch {
    return uri;
  }
}
