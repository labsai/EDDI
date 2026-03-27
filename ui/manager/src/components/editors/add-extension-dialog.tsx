import type React from "react";
import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  Plus,
  X,
  RefreshCw,
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
  Puzzle,
  ArrowLeft,
  FilePlus,
  FolderSearch,
  Plug,
} from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { useExtensionTypes } from "@/hooks/use-extensions-store";
import {
  EXTENSION_TYPE_INFO,
  getResourceSlugForExtension,
} from "@/lib/api/extensions";
import type { ExtensionDescriptor } from "@/lib/api/extensions";
import {
  getResourceType,
  getResourceDescriptors,
  createResource,
  type ResourceTypeConfig,
} from "@/lib/api/resources";
import { parseResourceUri } from "@/lib/api/agents";

/* ─── Icon map ─── */
const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
  Plug,
};

function getIcon(type: string): React.ComponentType<{ className?: string }> {
  const info = EXTENSION_TYPE_INFO[type];
  if (info && iconMap[info.icon]) return iconMap[info.icon]!;
  return Puzzle;
}

/** Build an eddi:// resource URI from resource type, ID, and version */
function buildEddiUri(rt: ResourceTypeConfig, id: string, version: number): string {
  // Extension type slug → eddi host mapping
  const slugToHost: Record<string, string> = {
    rules: "ai.labs.rules",
    apicalls: "ai.labs.apicalls",
    llm: "ai.labs.llm",
    output: "ai.labs.output",
    propertysetter: "ai.labs.property",
    mcpcalls: "ai.labs.mcpcalls",
  };
  const host = slugToHost[rt.slug] ?? `ai.labs.${rt.slug}`;
  return `eddi://${host}/${rt.store}/${rt.plural}/${id}?version=${version}`;
}

export interface AddExtensionResult {
  descriptor: ExtensionDescriptor;
  configUri?: string; // eddi:// URI if resource was created/selected
}

export interface AddExtensionDialogProps {
  open: boolean;
  onClose: () => void;
  onSelect: (result: AddExtensionResult) => void;
}

type Step = "pick-type" | "pick-resource";

interface ResourceDescItem {
  id: string;
  version: number;
  name: string;
  lastModifiedOn?: number;
  resource: string;
}

/**
 * Two-step dialog for adding an extension to the workflow pipeline.
 *
 * Step 1: Pick the extension type (Parser, Rules, LLM, etc.)
 * Step 2: For types with a resource store, choose "Create New" or "Use Existing"
 *         Types without a store (Parser, Output Template) skip step 2.
 */
export function AddExtensionDialog({
  open,
  onClose,
  onSelect,
}: AddExtensionDialogProps) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState("");
  const { data: extensionTypes, isLoading } = useExtensionTypes();

  const [step, setStep] = useState<Step>("pick-type");
  const [selectedType, setSelectedType] = useState<ExtensionDescriptor | null>(null);
  const [resourceType, setResourceType] = useState<ResourceTypeConfig | null>(null);
  const [existingResources, setExistingResources] = useState<ResourceDescItem[]>([]);
  const [loadingResources, setLoadingResources] = useState(false);
  const [creatingResource, setCreatingResource] = useState(false);

  const resetDialog = useCallback(() => {
    setStep("pick-type");
    setSelectedType(null);
    setResourceType(null);
    setExistingResources([]);
    setFilter("");
    setLoadingResources(false);
    setCreatingResource(false);
  }, []);

  const handleClose = useCallback(() => {
    resetDialog();
    onClose();
  }, [onClose, resetDialog]);

  // Step 1: User picks an extension type
  const handlePickType = useCallback(
    async (descriptor: ExtensionDescriptor) => {
      const resourceSlug = getResourceSlugForExtension(descriptor.type);

      if (!resourceSlug) {
        // No resource store (e.g. parser, output.template) — add directly
        onSelect({ descriptor });
        handleClose();
        return;
      }

      const rt = getResourceType(resourceSlug);
      if (!rt) {
        // Fallback: add without config URI
        onSelect({ descriptor });
        handleClose();
        return;
      }

      // Move to step 2: pick resource
      setSelectedType(descriptor);
      setResourceType(rt);
      setStep("pick-resource");

      // Load existing resources in background
      setLoadingResources(true);
      try {
        const descriptors = await getResourceDescriptors(rt, 200, 0, "");
        const items: ResourceDescItem[] = descriptors.map((d) => {
          const { id, version } = parseResourceUri(d.resource);
          return {
            id,
            version,
            name: d.name || id,
            lastModifiedOn: d.lastModifiedOn,
            resource: d.resource,
          };
        });
        setExistingResources(items);
      } catch {
        setExistingResources([]);
      } finally {
        setLoadingResources(false);
      }
    },
    [onSelect, handleClose]
  );

  // Step 2a: Create a new empty resource config
  const handleCreateNew = useCallback(async () => {
    if (!selectedType || !resourceType) return;
    setCreatingResource(true);
    try {
      const response = await createResource(resourceType, {});
      // Parse ID from Location header (eddi:// URI or relative path)
      const url = new URL(response.location, "http://dummy");
      const parts = url.pathname.split("/").filter(Boolean);
      const id = parts[parts.length - 1]!;
      const version = parseInt(url.searchParams.get("version") || "1", 10);

      const configUri = buildEddiUri(resourceType, id, version);
      toast.success(
        t("packageEditor.configCreated", "Config created — added to pipeline")
      );
      onSelect({ descriptor: selectedType, configUri });
      handleClose();
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setCreatingResource(false);
    }
  }, [selectedType, resourceType, onSelect, handleClose, t]);

  // Step 2b: Use an existing resource
  const handlePickExisting = useCallback(
    (item: ResourceDescItem) => {
      if (!selectedType) return;
      onSelect({ descriptor: selectedType, configUri: item.resource });
      handleClose();
    },
    [selectedType, onSelect, handleClose]
  );

  if (!open) return null;

  // Sort by pipeline order
  const sorted = [...(extensionTypes ?? [])].sort((a, b) => {
    const orderA = EXTENSION_TYPE_INFO[a.type]?.order ?? 99;
    const orderB = EXTENSION_TYPE_INFO[b.type]?.order ?? 99;
    return orderA - orderB;
  });

  const filtered = filter
    ? sorted.filter(
        (ext) =>
          ext.displayName.toLowerCase().includes(filter.toLowerCase()) ||
          ext.type.toLowerCase().includes(filter.toLowerCase())
      )
    : sorted;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      data-testid="add-extension-dialog"
    >
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={handleClose}
      />

      {/* Dialog */}
      <div className="relative z-10 w-full max-w-md rounded-xl border bg-card shadow-xl mx-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            {step === "pick-resource" ? (
              <button
                onClick={() => {
                  setStep("pick-type");
                  setSelectedType(null);
                  setResourceType(null);
                  setFilter("");
                }}
                className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
                data-testid="back-to-types"
              >
                <ArrowLeft className="h-5 w-5" />
              </button>
            ) : (
              <Plus className="h-5 w-5 text-primary" />
            )}
            <h3 className="text-lg font-semibold text-foreground">
              {step === "pick-type"
                ? t("packageEditor.addExtension", "Add Extension")
                : t("packageEditor.chooseConfig", "Choose Config")}
            </h3>
          </div>
          <button
            onClick={handleClose}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Step 1: Pick extension type */}
        {step === "pick-type" && (
          <>
            {/* Search */}
            <div className="p-4 pb-0">
              <input
                type="text"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                placeholder={t("common.search")}
                className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                data-testid="extension-search"
                autoFocus
              />
            </div>

            {/* Extension list */}
            <div className="max-h-80 divide-y divide-border overflow-y-auto p-2">
              {isLoading && (
                <div className="flex items-center justify-center py-8">
                  <RefreshCw className="h-5 w-5 animate-spin text-primary" />
                </div>
              )}

              {!isLoading && filtered.length === 0 && (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  {t("common.noResults")}
                </p>
              )}

              {filtered.map((ext) => {
                const Icon = getIcon(ext.type);
                const info = EXTENSION_TYPE_INFO[ext.type];
                const hasStore = !!getResourceSlugForExtension(ext.type);
                return (
                  <button
                    key={ext.type}
                    onClick={() => handlePickType(ext)}
                    className="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-start hover:bg-secondary/70 transition-colors"
                    data-testid={`ext-option-${ext.type}`}
                  >
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                      <Icon className="h-5 w-5 text-primary" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-foreground">
                        {ext.displayName || info?.label || ext.type}
                      </p>
                      <p className="text-xs text-muted-foreground truncate">
                        {ext.type}
                        {!hasStore && (
                          <span className="ms-1 text-muted-foreground/60">
                            (embedded config)
                          </span>
                        )}
                      </p>
                    </div>
                    <Plus className="h-4 w-4 shrink-0 text-primary" />
                  </button>
                );
              })}
            </div>

            {/* Footer hint */}
            <div className="border-t border-border p-3">
              <p className="text-xs text-center text-muted-foreground">
                {t(
                  "packageEditor.addHintDialog",
                  "Select an extension type to add to the pipeline"
                )}
              </p>
            </div>
          </>
        )}

        {/* Step 2: Pick resource (Create New or Use Existing) */}
        {step === "pick-resource" && selectedType && resourceType && (
          <>
            {/* Selected type info */}
            <div className="border-b border-border p-4">
              <div className="flex items-center gap-2">
                {(() => {
                  const SelIcon = getIcon(selectedType.type);
                  return <SelIcon className="h-4 w-4 text-primary" />;
                })()}
                <span className="text-sm font-medium text-foreground">
                  {selectedType.displayName ||
                    EXTENSION_TYPE_INFO[selectedType.type]?.label ||
                    selectedType.type}
                </span>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                {t(
                  "packageEditor.chooseConfigHint",
                  "Create a new config or link an existing one"
                )}
              </p>
            </div>

            {/* Create New button */}
            <div className="p-4 space-y-3">
              <button
                onClick={handleCreateNew}
                disabled={creatingResource}
                className="flex w-full items-center gap-3 rounded-xl border-2 border-dashed border-primary/30 bg-primary/5 px-4 py-4 text-start hover:bg-primary/10 hover:border-primary/50 transition-all disabled:opacity-50"
                data-testid="create-new-config"
              >
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/15">
                  <FilePlus className="h-5 w-5 text-primary" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold text-foreground">
                    {t("packageEditor.createNewConfig", "Create New Config")}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {t(
                      "packageEditor.createNewConfigHint",
                      "Creates an empty config you can edit later"
                    )}
                  </p>
                </div>
                {creatingResource && (
                  <RefreshCw className="h-4 w-4 animate-spin text-primary" />
                )}
              </button>

              {/* Existing resources separator */}
              {(existingResources.length > 0 || loadingResources) && (
                <div className="flex items-center gap-3">
                  <div className="h-px flex-1 bg-border" />
                  <span className="text-xs text-muted-foreground">
                    {t("packageEditor.orExisting", "or use existing")}
                  </span>
                  <div className="h-px flex-1 bg-border" />
                </div>
              )}

              {/* Loading existing */}
              {loadingResources && (
                <div className="flex items-center justify-center py-4">
                  <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
                </div>
              )}

              {/* Existing resource list */}
              {!loadingResources && existingResources.length > 0 && (
                <div className="max-h-48 divide-y divide-border overflow-y-auto rounded-lg border border-border">
                  {existingResources.map((item) => (
                    <button
                      key={`${item.id}-${item.version}`}
                      onClick={() => handlePickExisting(item)}
                      className="flex w-full items-center gap-3 px-3 py-2.5 text-start hover:bg-secondary/70 transition-colors"
                      data-testid={`existing-resource-${item.id}`}
                    >
                      <FolderSearch className="h-4 w-4 shrink-0 text-muted-foreground" />
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-foreground truncate">
                          {item.name}
                        </p>
                        <p className="text-xs text-muted-foreground truncate">
                          {item.id} · v{item.version}
                        </p>
                      </div>
                    </button>
                  ))}
                </div>
              )}

              {/* No existing resources */}
              {!loadingResources && existingResources.length === 0 && (
                <p className="text-xs text-center text-muted-foreground py-2">
                  {t(
                    "packageEditor.noExistingConfigs",
                    "No existing configs found — create a new one above"
                  )}
                </p>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
