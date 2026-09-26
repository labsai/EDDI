import { useState, useCallback, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { describeSaveError } from "@/lib/save-error";
import { parseResourceUri } from "@/lib/api/agents";
import { getResourceType, type ResourceTypeConfig } from "@/lib/api/resources";
import {
  cascadePartialResult,
  nextCascadeContext,
  type CascadeContext,
} from "@/lib/api/cascade-save";
import {
  useResource,
  useResourceVersions,
  useCascadeSave,
} from "@/hooks/use-resources";
import { useJsonSchema } from "@/hooks/use-json-schema";
import { ConfigEditorLayout } from "@/components/editors/config-editor-layout";
import { EDITOR_MAP, EXTENSION_TO_SLUG } from "@/components/editors/editor-registry";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/shared/error-state";
import { Layers, AlertTriangle } from "lucide-react";

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string };
}

interface StudioEditorPanelProps {
  workflowStep: WorkflowStep;
  /** Agent ID for cascade context */
  agentId: string;
  /** Agent version for cascade context */
  agentVersion: number;
  /** Workflow ID for cascade context */
  workflowId: string;
  /** Workflow version for cascade context */
  workflowVersion: number;
  /**
   * The workflow version the agent references, when a save that stopped after
   * the workflow hop left it behind `workflowVersion` (see `CascadeContext`).
   */
  agentWorkflowVersion?: number;
  /**
   * Called with the cascade context the NEXT save must use — after a save, and
   * after one that failed partway, with the versions that now exist.
   *
   * The page owns these versions because every stage shares them: each stage's
   * panel is a separate mount, so versions kept here were lost the moment the
   * user selected the next stage, whose first save then 409'd on the workflow
   * and left an orphaned resource version behind.
   */
  onCascadeContextChange?: (next: CascadeContext) => void;
  /**
   * The newest version of this stage's resource that a save from this page has
   * created — including one written by a save that then failed at the workflow
   * hop, which the workflow step's URI does not show. The panel never starts
   * below it, so returning to the stage after such a failure does not address
   * the superseded version.
   */
  savedResourceVersion?: number;
  /** Called with every resource version a save creates (see `savedResourceVersion`). */
  onResourceVersionSaved?: (resourceId: string, version: number) => void;
}

// ==================== Component ====================

export function StudioEditorPanel({
  workflowStep,
  agentId,
  agentVersion,
  workflowId,
  workflowVersion,
  agentWorkflowVersion,
  onCascadeContextChange,
  savedResourceVersion,
  onResourceVersionSaved,
}: StudioEditorPanelProps) {
  const { t } = useTranslation();

  // Parse the extension type and URI
  const slug = EXTENSION_TO_SLUG[workflowStep.type] ?? "";
  const rt: ResourceTypeConfig | undefined = getResourceType(slug);
  const uri = workflowStep.config?.uri ?? "";

  // Parse resource ID and version from the URI
  const { resourceId, uriVersion } = useMemo(() => {
    if (!uri) return { resourceId: "", uriVersion: 1 };
    try {
      const parsed = parseResourceUri(uri);
      return { resourceId: parsed.id, uriVersion: parsed.version };
    } catch {
      return { resourceId: "", uriVersion: 1 };
    }
  }, [uri]);
  const resourceVersion = Math.max(uriVersion, savedResourceVersion ?? 0);

  // Version state (starts from URI but can be changed via version picker)
  const [currentVersion, setCurrentVersion] = useState(resourceVersion);

  // Follow the step when its version moves — a save, or a refetched pipeline.
  // The page keys this panel by stage and resource id, not by URI, so a save no
  // longer remounts it (which discarded anything typed meanwhile).
  useEffect(() => {
    setCurrentVersion(resourceVersion);
  }, [resourceVersion]);

  // Fetch resource data
  const { data, isLoading, isError, refetch } = useResource(
    slug,
    resourceId,
    currentVersion,
  );

  // Fetch version list
  const { data: versionDescriptors } = useResourceVersions(slug, resourceId);

  // JSON schema for validation
  const { data: jsonSchema } = useJsonSchema(slug || undefined);

  // Save with cascade
  const cascadeSave = useCascadeSave(slug);

  // Track save success for inline feedback
  const [saveSuccess, setSaveSuccess] = useState(false);
  useEffect(() => {
    if (saveSuccess) {
      const timer = setTimeout(() => setSaveSuccess(false), 3000);
      return () => clearTimeout(timer);
    }
  }, [saveSuccess]);

  // Build version list
  const versions = versionDescriptors
    ? versionDescriptors.map((d) => {
        const match = d.resource?.match(/\?version=(\d+)/);
        return {
          version: match ? parseInt(match[1] ?? "1", 10) : 1,
          lastModifiedOn: d.lastModifiedOn,
        };
      })
    : [{ version: currentVersion }];

  // Cascade context for auto-propagation to parent workflow and agent. The
  // versions come from the page (see `onCascadeContextChange`).
  const cascadeContext = useMemo<CascadeContext>(() => ({
    workflowId,
    workflowVersion,
    agentId,
    agentVersion,
    ...(agentWorkflowVersion !== undefined ? { agentWorkflowVersion } : {}),
  }), [workflowId, workflowVersion, agentId, agentVersion, agentWorkflowVersion]);

  const handleSave = useCallback(
    (jsonString: string) => {
      try {
        const parsed = JSON.parse(jsonString);
        cascadeSave.mutate(
          {
            id: resourceId,
            version: currentVersion,
            body: parsed,
            context: cascadeContext,
          },
          {
            onSuccess: (result) => {
              toast.success(t("editor.saved", "Saved successfully"));
              setSaveSuccess(true);
              setCurrentVersion(result.newResourceVersion);
              onResourceVersionSaved?.(resourceId, result.newResourceVersion);
              // The next save — of this stage or any other — builds on these.
              onCascadeContextChange?.(nextCascadeContext(cascadeContext, result));
            },
            onError: (err) => {
              // A cascade that failed partway already bumped the resource (and
              // perhaps the workflow): adopt those, or every retry 409s.
              const partial = cascadePartialResult(err);
              if (partial?.newResourceVersion !== undefined) {
                setCurrentVersion(partial.newResourceVersion);
                onResourceVersionSaved?.(resourceId, partial.newResourceVersion);
              }
              if (partial?.retryContext) {
                onCascadeContextChange?.(partial.retryContext);
              }
              toast.error(describeSaveError(err, t));
            },
          },
        );
      } catch {
        toast.error(t("editor.invalidJson", "Invalid JSON"));
      }
    },
    [resourceId, currentVersion, cascadeSave, cascadeContext, onCascadeContextChange, onResourceVersionSaved, t],
  );

  // ---- No URI / unsupported type ----
  if (!uri || !rt) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center p-6 text-center">
        <AlertTriangle className="h-10 w-10 text-muted-foreground/30 mb-3" />
        <p className="text-sm text-muted-foreground">
          {!uri
            ? t("studio.noConfig", "This pipeline stage has no configuration URI")
            : t("studio.unsupportedType", "Editor not available for this extension type")}
        </p>
        <p className="mt-1 text-xs text-muted-foreground/60 font-mono">
          {workflowStep.type}
        </p>
      </div>
    );
  }

  // ---- Loading ----
  if (isLoading) {
    return (
      <div className="flex-1 p-6 space-y-4">
        <div className="flex gap-3">
          <Skeleton className="h-8 w-24" />
          <Skeleton className="h-8 w-24" />
        </div>
        <Skeleton className="h-[400px] w-full rounded-xl" />
      </div>
    );
  }

  // ---- Error ----
  if (isError) {
    return (
      <div className="flex-1 p-6">
        <ErrorState
          message={t("common.error", "An error occurred")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry", "Retry")}
        />
      </div>
    );
  }

  // ---- Editor ----
  const typeName = t(`${rt.labelKey}.name`, slug);

  return (
    <div className="flex-1 overflow-y-auto p-4" data-testid="studio-editor-panel">
      <ConfigEditorLayout
        typeName={typeName}
        resourceId={resourceId}
        data={JSON.stringify(data, null, 2)}
        versions={versions}
        currentVersion={currentVersion}
        onVersionChange={setCurrentVersion}
        onSave={handleSave}
        isSaving={cascadeSave.isPending}
        saveSuccess={saveSuccess}
        saveError={
          cascadeSave.isError
            ? t("editor.saveError", "Failed to save")
            : undefined
        }
        renderFormEditor={EDITOR_MAP[slug]}
        jsonSchema={jsonSchema}
      />
    </div>
  );
}

// ==================== Empty Placeholder ====================

export function StudioEditorEmpty() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-1 flex-col items-center justify-center p-6 text-center">
      <Layers className="h-16 w-16 text-muted-foreground/15 mb-4" />
      <p className="text-sm font-medium text-foreground">
        {t("studio.selectStage", "Click a pipeline stage to open its editor")}
      </p>
      <p className="mt-1.5 text-xs text-muted-foreground max-w-xs">
        {t("studio.selectStageHint", "Select any extension in the pipeline to view and edit its configuration inline")}
      </p>
    </div>
  );
}
