import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  FileDown,
  Search,
  Loader2,
  AlertCircle,
  RefreshCw,
  Check,
  Plus,
  X,
} from "lucide-react";
import {
  discoverEndpoints,
  type DiscoverEndpointsResult,
} from "@/lib/api/openapi-discover";
import { createResource, getResourceType } from "@/lib/api/resources";
import { updateDescriptor } from "@/lib/api/descriptors";
import { parseResourceUri } from "@/lib/api/agents";
import type { WorkflowExtension } from "@/lib/api/workflows";
import type { HttpCall } from "./httpcalls-editor";
import { isValidUrl } from "@/lib/utils";

export interface ImportOpenApiDialogProps {
  open: boolean;
  onClose: () => void;
  /** Called with the new WorkflowExtension entries so the parent can append them */
  onImport: (extensions: WorkflowExtension[]) => void;
}

/**
 * Workflow-level "Import from OpenAPI" dialog.
 * Discovers endpoints from an OpenAPI spec, lets the user select groups/endpoints,
 * then creates one ApiCallsConfiguration per selected group and returns
 * WorkflowExtension entries for the pipeline.
 */
export function ImportOpenApiDialog({
  open,
  onClose,
  onImport,
}: ImportOpenApiDialogProps) {
  const { t } = useTranslation();

  const [specUrl, setSpecUrl] = useState("");
  const [result, setResult] = useState<DiscoverEndpointsResult | null>(null);
  const [isDiscovering, setIsDiscovering] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const specUrlValid = specUrl.trim().length > 0 && isValidUrl(specUrl.trim());

  const handleDiscover = useCallback(async () => {
    if (!specUrlValid) return;
    setIsDiscovering(true);
    setError(null);
    setResult(null);
    setSelected(new Set());
    try {
      const r = await discoverEndpoints(specUrl);
      setResult(r);
      // Select all groups by default
      const keys = new Set<string>();
      for (const groupName of Object.keys(r.groups)) {
        keys.add(groupName);
      }
      setSelected(keys);
    } catch (err: unknown) {
      const msg =
        err && typeof err === "object" && "message" in err
          ? String((err as { message: string }).message)
          : t("httpcallsEditor.discoveryError", "Could not parse OpenAPI spec");
      setError(msg);
    } finally {
      setIsDiscovering(false);
    }
  }, [specUrl, specUrlValid, t]);

  const toggleGroup = useCallback((group: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(group)) next.delete(group);
      else next.add(group);
      return next;
    });
  }, []);

  const handleImport = useCallback(async () => {
    if (!result) return;
    const apicallsRt = getResourceType("apicalls");
    if (!apicallsRt) return;

    setIsImporting(true);
    setError(null);
    try {
      // Build all configs in parallel for fast import
      const selectedGroups = Object.entries(result.groups).filter(
        ([name]) => selected.has(name)
      );

      const createGroupConfig = async ([groupName, group]: [string, { targetServerUrl: string; httpCalls: HttpCall[] }]) => {
        const config = {
          targetServerUrl: group.targetServerUrl,
          httpCalls: group.httpCalls,
        };
        const createResult = await createResource(apicallsRt, config);
        const { id, version } = parseResourceUri(createResult.location);

        // Name the descriptor for better UX
        await updateDescriptor(id, version, {
          name: `${result.title} — ${groupName}`,
          description: `Auto-imported from ${specUrl} (${group.httpCalls.length} endpoints)`,
        });

        return {
          type: "ai.labs.apicalls" as const,
          extensions: {},
          config: {
            uri: `eddi://ai.labs.apicalls/apicallstore/apicalls/${id}?version=${version}`,
          },
        };
      };

      const newExtensions = await Promise.all(
        selectedGroups.map(createGroupConfig)
      );

      onImport(newExtensions);
      // Reset state
      setResult(null);
      setSpecUrl("");
      setSelected(new Set());
      onClose();
    } catch (err: unknown) {
      const msg =
        err && typeof err === "object" && "message" in err
          ? String((err as { message: string }).message)
          : t("common.error", "An error occurred");
      setError(msg);
    } finally {
      setIsImporting(false);
    }
  }, [result, selected, onImport, onClose, specUrl, t]);

  const handleClose = useCallback(() => {
    setSpecUrl("");
    setResult(null);
    setError(null);
    setSelected(new Set());
    onClose();
  }, [onClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      data-testid="import-openapi-dialog"
    >
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={handleClose} />
      <div className="relative z-10 w-full max-w-lg rounded-xl border bg-card shadow-xl mx-4 max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border p-5 shrink-0">
          <div className="flex items-center gap-2">
            <FileDown className="h-5 w-5 text-primary" />
            <h3 className="text-lg font-semibold text-foreground">
              {t("workflowEditor.importOpenApi", "Import from OpenAPI")}
            </h3>
          </div>
          <button
            onClick={handleClose}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-5 space-y-4">
          {/* Spec URL */}
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">
              {t("httpcallsEditor.openApiSpecUrl", "OpenAPI Spec URL")}
            </label>
            <div className="flex gap-2">
              <input
                type="url"
                value={specUrl}
                onChange={(e) => setSpecUrl(e.target.value)}
                placeholder={t(
                  "httpcallsEditor.specUrlPlaceholder",
                  "https://api.example.com/openapi.json"
                )}
                className="h-9 flex-1 rounded-lg border border-input bg-background px-3 font-mono text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid="import-spec-url-input"
                autoFocus
                onKeyDown={(e) => {
                  if (e.key === "Enter" && specUrlValid && !isDiscovering) {
                    e.preventDefault();
                    handleDiscover();
                  }
                }}
              />
              <button
                type="button"
                onClick={handleDiscover}
                disabled={isDiscovering || !specUrlValid}
                title={specUrl.trim() && !specUrlValid ? t("httpcallsEditor.invalidUrl", "Enter a valid http:// or https:// URL") : undefined}
                className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                data-testid="import-discover-btn"
              >
                {isDiscovering ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Search className="h-4 w-4" />
                )}
                {isDiscovering
                  ? t("httpcallsEditor.discovering", "Parsing…")
                  : t("httpcallsEditor.discoverEndpoints", "Discover")}
              </button>
            </div>
          </div>

          {/* Error */}
          {error && (
            <div className="flex items-center gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-3 py-3">
              <AlertCircle className="h-4 w-4 shrink-0 text-destructive" />
              <p className="flex-1 text-xs text-destructive">{error}</p>
              <button
                type="button"
                onClick={handleDiscover}
                className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-destructive hover:bg-destructive/10 transition-colors"
              >
                <RefreshCw className="h-3 w-3" />
                {t("httpcallsEditor.retry", "Retry")}
              </button>
            </div>
          )}

          {/* Discovering */}
          {isDiscovering && (
            <div className="flex items-center justify-center gap-2 py-8 text-sm text-muted-foreground">
              <Loader2 className="h-5 w-5 animate-spin text-primary" />
              {t("httpcallsEditor.discovering", "Parsing…")}
            </div>
          )}

          {/* Groups */}
          {result && (
            <div className="space-y-2">
              <p className="text-xs font-medium text-muted-foreground">
                {t("workflowEditor.selectGroups", "Select API groups to import as separate API Call extensions")}
              </p>
              <p className="text-xs text-muted-foreground">
                {result.title} — {result.endpointCount} {t("workflowEditor.endpointsTotal", "endpoints total")}
              </p>
              <div className="space-y-2">
                {Object.entries(result.groups).map(([groupName, group]) => {
                  const isSelected = selected.has(groupName);
                  return (
                    <label
                      key={groupName}
                      className={`flex items-center gap-3 rounded-lg border px-4 py-3 transition-colors cursor-pointer ${
                        isSelected
                          ? "border-primary/30 bg-primary/5"
                          : "border-border hover:border-border/80 bg-background"
                      }`}
                      data-testid={`import-group-${groupName}`}
                    >
                      <div
                        className={`h-4 w-4 rounded border flex items-center justify-center transition-colors ${
                          isSelected
                            ? "bg-primary border-primary text-primary-foreground"
                            : "border-input hover:border-primary"
                        }`}
                      >
                        {isSelected && <Check className="h-3 w-3" />}
                      </div>
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => toggleGroup(groupName)}
                        className="sr-only"
                      />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm font-semibold text-foreground capitalize">
                          {groupName}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {group.httpCalls.length} {t("workflowEditor.endpoints", "endpoints")}:
                          {" "}
                          {group.httpCalls
                            .slice(0, 3)
                            .map(
                              (c: HttpCall) =>
                                `${(c.request?.method ?? "GET").toUpperCase()} ${c.request?.path ?? ""}`
                            )
                            .join(", ")}
                          {group.httpCalls.length > 3 ? ", …" : ""}
                        </p>
                      </div>
                      <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
                        {group.httpCalls.length}
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        {result && (
          <div className="flex items-center justify-between border-t border-border p-4 shrink-0">
            <span className="text-xs text-muted-foreground">
              {selected.size} / {Object.keys(result.groups).length}{" "}
              {t("workflowEditor.groupsSelected", "groups selected")}
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={handleClose}
                className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-secondary"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                type="button"
                onClick={handleImport}
                disabled={selected.size === 0 || isImporting}
                className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                data-testid="import-confirm-btn"
              >
                {isImporting ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Plus className="h-4 w-4" />
                )}
                {isImporting
                  ? t("workflowEditor.importing", "Importing…")
                  : t("workflowEditor.importSelected", "Import Selected Groups")}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
