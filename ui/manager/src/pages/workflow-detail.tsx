import { useState, useCallback, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useParams, Link, useNavigate } from "react-router-dom";
import {
  ArrowLeft,
  Workflow,
  Plus,
  Trash2,
  RefreshCw,
  AlertCircle,
  Settings,
  Save,
  Undo2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import {
  useWorkflow,
  useUpdateWorkflow,
  useDeleteWorkflow,
  useWorkflowVersions,
} from "@/hooks/use-workflows";

import { parseResourceUri } from "@/lib/api/agents";
import type { WorkflowExtension } from "@/lib/api/workflows";
import type { ExtensionDescriptor } from "@/lib/api/extensions";
import {
  PipelineBuilder,
  type PipelineItem,
} from "@/components/editors/pipeline-builder";
import { AddExtensionDialog } from "@/components/editors/add-extension-dialog";


/* ─── Main page ─── */
export function WorkflowDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [version, setVersion] = useState(1);
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [localExtensions, setLocalExtensions] = useState<
    WorkflowExtension[] | null
  >(null);
  const [saveMessage, setSaveMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  const {
    data: pkg,
    isLoading,
    isError,
    refetch,
  } = useWorkflow(id!, version);
  const { data: versionDescriptors } = useWorkflowVersions(id!);
  const updateMutation = useUpdateWorkflow();
  const deleteMutation = useDeleteWorkflow();

  // Version picker data
  const versions = useMemo(() => {
    if (!versionDescriptors) return [];
    return versionDescriptors
      .map((d) => {
        const { version: v } = parseResourceUri(d.resource);
        return { version: v, lastModifiedOn: d.lastModifiedOn };
      })
      .sort((a, b) => b.version - a.version);
  }, [versionDescriptors]);

  // Use local state if user has made edits, otherwise use server data
  const currentExtensions = useMemo(
    () => localExtensions ?? pkg?.workflowSteps ?? [],
    [localExtensions, pkg?.workflowSteps]
  );
  const serverExtensions = pkg?.workflowSteps ?? [];
  const isDirty =
    localExtensions !== null &&
    JSON.stringify(localExtensions) !== JSON.stringify(serverExtensions);

  // Build pipeline items from current extensions
  const pipelineItems: PipelineItem[] = currentExtensions.map((ext, i) => ({
    id: `ext-${i}`,
    index: i,
    extension: ext,
  }));

  // Reset local state when server data changes (version switch)
  useEffect(() => {
    setLocalExtensions(null);
  }, [pkg?.workflowSteps]);

  // Clear save message after 3s
  useEffect(() => {
    if (saveMessage) {
      const timer = setTimeout(() => setSaveMessage(null), 3000);
      return () => clearTimeout(timer);
    }
  }, [saveMessage]);

  const handleReorder = useCallback(
    (newItems: PipelineItem[]) => {
      setLocalExtensions(newItems.map((item) => item.extension));
    },
    []
  );

  const handleRemoveExtension = useCallback(
    (index: number) => {
      const updated = currentExtensions.filter((_, i) => i !== index);
      setLocalExtensions(updated);
    },
    [currentExtensions]
  );

  const handleAddExtension = useCallback(
    (descriptor: ExtensionDescriptor) => {
      const newExt: WorkflowExtension = {
        type: descriptor.type,
        extensions: {},
        config: {},
      };
      setLocalExtensions([...currentExtensions, newExt]);
      setShowAddDialog(false);
    },
    [currentExtensions]
  );

  const handleSave = useCallback(async () => {
    if (!isDirty || !localExtensions) return;
    try {
      await updateMutation.mutateAsync({
        id: id!,
        version,
        config: { workflowSteps: localExtensions },
      });
      setSaveMessage({
        type: "success",
        text: t("packageEditor.saved", "Workflow saved successfully"),
      });
      setLocalExtensions(null);
    } catch {
      setSaveMessage({
        type: "error",
        text: t("packageEditor.saveError", "Failed to save package"),
      });
    }
  }, [isDirty, localExtensions, updateMutation, id, version, t]);

  const handleDiscard = useCallback(() => {
    setLocalExtensions(null);
  }, []);

  async function handleDelete() {
    if (
      window.confirm(
        t(
          "packages.confirmDelete",
          "Are you sure you want to delete this package?"
        )
      )
    ) {
      await deleteMutation.mutateAsync({ id: id!, version });
      navigate("/manage/workflows");
    }
  }

  const handleVersionChange = useCallback((v: number) => {
    setVersion(v);
    setLocalExtensions(null);
  }, []);

  /* ─── Loading / Error states ─── */
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
          <div className="flex items-center gap-3">
            <Workflow className="h-8 w-8 text-primary" />
            <div>
              <h1 className="text-3xl font-bold text-foreground">
                {t("packageEditor.title", "Workflow Editor")}
              </h1>
              <p className="font-mono text-sm text-muted-foreground">
                ID: {id}
              </p>
            </div>
          </div>

          {/* Version picker */}
          {versions.length > 0 && (
            <VersionSelect
              versions={versions}
              current={version}
              onChange={handleVersionChange}
              disabled={isDirty}
            />
          )}
        </div>

        {/* Actions */}
        <div className="flex flex-wrap items-center gap-2">
          {/* Save feedback */}
          {saveMessage && (
            <span
              className={cn(
                "text-xs font-medium",
                saveMessage.type === "success"
                  ? "text-emerald-600 dark:text-emerald-400"
                  : "text-destructive"
              )}
              data-testid="save-feedback"
            >
              {saveMessage.type === "success" ? "✓" : "✕"} {saveMessage.text}
            </span>
          )}

          {/* Dirty indicator */}
          {isDirty && (
            <span
              className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-400"
              data-testid="dirty-indicator"
            >
              <AlertCircle className="h-3 w-3" />
              {t("editor.dirty", "Unsaved changes")}
            </span>
          )}

          {/* Discard */}
          <button
            onClick={handleDiscard}
            disabled={!isDirty || updateMutation.isPending}
            className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-2 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary active:scale-[0.98] disabled:opacity-50"
            data-testid="discard-btn"
          >
            <Undo2 className="h-4 w-4" />
            {t("editor.discard", "Discard")}
          </button>

          {/* Save */}
          <button
            onClick={handleSave}
            disabled={!isDirty || updateMutation.isPending}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.98] disabled:opacity-50"
            data-testid="save-btn"
          >
            <Save className="h-4 w-4" />
            {updateMutation.isPending
              ? t("editor.saving", "Saving...")
              : t("editor.save", "Save")}
          </button>

          {/* Delete */}
          <button
            onClick={handleDelete}
            className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
            data-testid="delete-pkg-btn"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Pipeline section */}
      <section className="rounded-xl border bg-card shadow-sm">
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            <Workflow className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">
              {t("packageEditor.pipeline", "Extension Pipeline")}
            </h2>
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
              {currentExtensions.length}
            </span>
          </div>
          <button
            onClick={() => setShowAddDialog(true)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/20 transition-colors"
            data-testid="add-extension-btn"
          >
            <Plus className="h-4 w-4" />
            {t("packageEditor.addExtension", "Add Extension")}
          </button>
        </div>

        <PipelineBuilder
          items={pipelineItems}
          onChange={handleReorder}
          onRemove={handleRemoveExtension}
          disabled={updateMutation.isPending}
        />
      </section>

      {/* Add extension dialog */}
      <AddExtensionDialog
        open={showAddDialog}
        onClose={() => setShowAddDialog(false)}
        onSelect={handleAddExtension}
      />

      {/* Raw config (collapsible) */}
      <RawConfigSection config={pkg} />
    </div>
  );
}

/* ─── Sub-components ─── */

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link
      to="/manage/workflows"
      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
    >
      <ArrowLeft className="h-4 w-4" />
      {t("packageDetail.backToWorkflows", "Back to Workflows")}
    </Link>
  );
}

function VersionSelect({
  versions,
  current,
  onChange,
  disabled,
}: {
  versions: { version: number; lastModifiedOn?: number }[];
  current: number;
  onChange: (v: number) => void;
  disabled?: boolean;
}) {
  if (versions.length <= 1) {
    return (
      <span
        className="inline-flex items-center gap-1.5 rounded-md bg-muted px-2.5 py-1 text-xs font-medium text-muted-foreground"
        data-testid="version-badge"
      >
        v{current}
      </span>
    );
  }

  return (
    <select
      value={current}
      onChange={(e) => onChange(Number(e.target.value))}
      disabled={disabled}
      className="rounded-md border border-input bg-background px-2.5 py-1 text-xs font-medium text-foreground shadow-sm transition-colors hover:bg-secondary focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1 disabled:opacity-50"
      data-testid="version-picker"
    >
      {versions.map((v) => (
        <option key={v.version} value={v.version}>
          v{v.version}
          {v.lastModifiedOn
            ? ` — ${formatRelativeTime(v.lastModifiedOn)}`
            : ""}
        </option>
      ))}
    </select>
  );
}

function formatRelativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp;
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(timestamp).toLocaleDateString();
}

function RawConfigSection({
  config,
}: {
  config: { workflowSteps?: WorkflowExtension[] };
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
