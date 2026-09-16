import { useState, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { X, Download, Loader2, Lock } from "lucide-react";
import { usePreviewExport, useExportSelective } from "@/hooks/use-backup";
import { ResourceTypeBadge } from "@/components/shared/resource-type-badge";
import { Button } from "@/components/ui/button";
import type { ExportableResource } from "@/lib/api/backup";

interface ExportAgentDialogProps {
  open: boolean;
  onClose: () => void;
  agentId: string;
  agentVersion: number;
}

interface ResourceGroup {
  workflowId: string | null;
  workflowName: string | null;
  workflowIndex: number;
  resources: ExportableResource[];
}

export function ExportAgentDialog({
  open,
  onClose,
  agentId,
  agentVersion,
}: ExportAgentDialogProps) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const { data: preview, isLoading, isError, error } = usePreviewExport(
    agentId,
    agentVersion,
    open
  );

  const exportMutation = useExportSelective();

  // Initialize selection when preview loads
  const allResourceIds = useMemo(() => {
    if (!preview) return [];
    return preview.resources.map((r) => r.resourceId);
  }, [preview]);

  // Auto-select all when preview loads (or dialog re-opens)
  useEffect(() => {
    if (allResourceIds.length > 0) {
      setSelected(new Set(allResourceIds));
    }
  }, [allResourceIds]);

  const requiredIds = useMemo(() => {
    if (!preview) return new Set<string>();
    return new Set(preview.resources.filter((r) => r.required).map((r) => r.resourceId));
  }, [preview]);

  // Group resources by parentWorkflowId
  const groups = useMemo((): ResourceGroup[] => {
    if (!preview) return [];
    const map = new Map<string, ResourceGroup>();

    for (const r of preview.resources) {
      if (r.resourceType === "agent") continue; // handled separately

      const key = r.parentWorkflowId || "__snippets__";
      if (!map.has(key)) {
        // Find the workflow resource for this group
        const wfResource = r.parentWorkflowId
          ? preview.resources.find(
              (w) => w.resourceId === r.parentWorkflowId && w.resourceType === "workflow"
            )
          : null;
        map.set(key, {
          workflowId: r.parentWorkflowId,
          workflowName: wfResource?.name || r.parentWorkflowId,
          workflowIndex: wfResource?.workflowIndex ?? -1,
          resources: [],
        });
      }
      if (r.resourceType !== "workflow") {
        map.get(key)!.resources.push(r);
      }
    }

    return Array.from(map.values()).sort((a, b) => a.workflowIndex - b.workflowIndex);
  }, [preview]);

  const agentResource = preview?.resources.find((r) => r.resourceType === "agent");
  const workflowResources = preview?.resources.filter((r) => r.resourceType === "workflow") ?? [];

  function toggleResource(id: string) {
    if (requiredIds.has(id)) return;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (selected.size === allResourceIds.length) {
      // Deselect all non-required
      setSelected(new Set(requiredIds));
    } else {
      setSelected(new Set(allResourceIds));
    }
  }

  function handleExport() {
    exportMutation.mutate(
      {
        agentId,
        version: agentVersion,
        selectedResourceIds: Array.from(selected),
      },
      { onSuccess: () => handleClose() }
    );
  }

  function handleClose() {
    setSelected(new Set());
    onClose();
  }

  if (!open) return null;

  const isExporting = exportMutation.isPending;

  return (
    <>
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={!isExporting ? handleClose : undefined}
      />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-lg rounded-xl border bg-card p-6 shadow-2xl max-h-[80vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
          data-testid="export-agent-dialog"
        >
          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-foreground">
              {t("exportDialog.title", "Export Agent")}
            </h2>
            <button
              onClick={handleClose}
              disabled={isExporting}
              className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-50"
            >
              <X className="h-5 w-5" />
            </button>
          </div>

          {/* Loading */}
          {isLoading && (
            <div className="flex-1 flex flex-col items-center justify-center gap-3 py-12">
              <Loader2 className="h-8 w-8 animate-spin text-primary" />
              <p className="text-sm text-muted-foreground">
                {t("exportDialog.loading", "Loading resource tree...")}
              </p>
            </div>
          )}

          {/* Error */}
          {isError && (
            <div className="flex-1 flex flex-col items-center justify-center gap-2 py-12">
              <p className="text-sm text-destructive">
                {(error as Error)?.message || t("common.error")}
              </p>
            </div>
          )}

          {/* Resource tree */}
          {preview && !isLoading && (
            <>
              <p className="text-sm text-muted-foreground mb-3">
                {t("exportDialog.selectResources", "Select resources to export")}
              </p>

              <div className="flex-1 overflow-auto min-h-0 space-y-1 rounded-lg border p-3 bg-secondary/20">
                {/* Agent root */}
                {agentResource && (
                  <ResourceRow
                    resource={agentResource}
                    checked={selected.has(agentResource.resourceId)}
                    required
                    indent={0}
                  />
                )}

                {/* Workflow groups */}
                {workflowResources.map((wf) => {
                  const group = groups.find((g) => g.workflowId === wf.resourceId);
                  return (
                    <div key={wf.resourceId}>
                      <ResourceRow
                        resource={wf}
                        checked={selected.has(wf.resourceId)}
                        required={wf.required}
                        indent={1}
                      />
                      {group?.resources.map((r) => (
                        <ResourceRow
                          key={r.resourceId}
                          resource={r}
                          checked={selected.has(r.resourceId)}
                          required={r.required}
                          indent={2}
                          onToggle={() => toggleResource(r.resourceId)}
                        />
                      ))}
                    </div>
                  );
                })}

                {/* Snippets group */}
                {groups
                  .filter((g) => !g.workflowId)
                  .map((g) =>
                    g.resources.length > 0 ? (
                      <div key="snippets">
                        <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mt-2 mb-1 ps-2">
                          {t("exportDialog.snippets", "Snippets")}
                        </p>
                        {g.resources.map((r) => (
                          <ResourceRow
                            key={r.resourceId}
                            resource={r}
                            checked={selected.has(r.resourceId)}
                            required={r.required}
                            indent={1}
                            onToggle={() => toggleResource(r.resourceId)}
                          />
                        ))}
                      </div>
                    ) : null
                  )}
              </div>

              {/* Footer */}
              <div className="flex items-center justify-between pt-4 border-t mt-4">
                <div className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={selected.size === allResourceIds.length}
                    onChange={toggleAll}
                    className="accent-primary"
                    data-testid="select-all-checkbox"
                  />
                  <span className="text-xs text-muted-foreground">
                    {selected.size === allResourceIds.length
                      ? t("exportDialog.allSelected", "All resources selected")
                      : t("exportDialog.countSelected", "{{count}} of {{total}} resources selected", {
                          count: selected.size,
                          total: allResourceIds.length,
                        })}
                  </span>
                </div>
                <Button
                  onClick={handleExport}
                  disabled={isExporting || selected.size === 0}
                  data-testid="export-confirm-btn"
                >
                  {isExporting ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Download className="h-4 w-4" />
                  )}
                  {t("exportDialog.exportSelected", "Export Selected")}
                </Button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}

function ResourceRow({
  resource,
  checked,
  required,
  indent,
  onToggle,
}: {
  resource: ExportableResource;
  checked: boolean;
  required: boolean;
  indent: number;
  onToggle?: () => void;
}) {
  const paddingClass = indent === 0 ? "ps-2" : indent === 1 ? "ps-6" : "ps-10";

  return (
    <label
      className={`flex items-center gap-2 rounded-md px-2 py-1.5 text-sm cursor-pointer hover:bg-secondary/50 transition-colors ${paddingClass} ${
        checked ? "text-foreground" : "text-muted-foreground"
      }`}
    >
      <input
        type="checkbox"
        checked={checked}
        disabled={required}
        onChange={onToggle}
        className="accent-primary disabled:opacity-60"
      />
      <ResourceTypeBadge type={resource.resourceType} />
      <span className="truncate flex-1">
        {resource.name || resource.resourceId.slice(0, 12)}
      </span>
      {required && (
        <Lock className="h-3 w-3 text-muted-foreground/50 shrink-0" />
      )}
    </label>
  );
}
