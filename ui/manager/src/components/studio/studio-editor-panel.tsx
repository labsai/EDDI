import { useState, useCallback, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { parseResourceUri } from "@/lib/api/agents";
import { getResourceType, type ResourceTypeConfig } from "@/lib/api/resources";
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
}

// ==================== Component ====================

export function StudioEditorPanel({
  workflowStep,
  agentId,
  agentVersion,
  workflowId,
  workflowVersion,
}: StudioEditorPanelProps) {
  const { t } = useTranslation();

  // Parse the extension type and URI
  const slug = EXTENSION_TO_SLUG[workflowStep.type] ?? "";
  const rt: ResourceTypeConfig | undefined = getResourceType(slug);
  const uri = workflowStep.config?.uri ?? "";

  // Parse resource ID and version from the URI
  const { resourceId, resourceVersion } = useMemo(() => {
    if (!uri) return { resourceId: "", resourceVersion: 1 };
    try {
      const parsed = parseResourceUri(uri);
      return { resourceId: parsed.id, resourceVersion: parsed.version };
    } catch {
      return { resourceId: "", resourceVersion: 1 };
    }
  }, [uri]);

  // Version state (starts from URI but can be changed via version picker)
  const [currentVersion, setCurrentVersion] = useState(resourceVersion);

  // Sync version when the selected step changes (different URI)
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

  // Cascade context for auto-propagation to parent workflow and agent
  // Track versions in local state so they update after cascade saves
  const [localWorkflowVersion, setLocalWorkflowVersion] = useState(workflowVersion);
  const [localAgentVersion, setLocalAgentVersion] = useState(agentVersion);

  // Reset when the parent re-mounts with different props
  useEffect(() => {
    setLocalWorkflowVersion(workflowVersion);
    setLocalAgentVersion(agentVersion);
  }, [workflowVersion, agentVersion]);

  const cascadeContext = useMemo(() => ({
    workflowId,
    workflowVersion: localWorkflowVersion,
    agentId,
    agentVersion: localAgentVersion,
  }), [workflowId, localWorkflowVersion, agentId, localAgentVersion]);

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
              // Update cascade context versions so next save uses correct versions
              if (result.newWorkflowVersion) setLocalWorkflowVersion(result.newWorkflowVersion);
              if (result.newAgentVersion) setLocalAgentVersion(result.newAgentVersion);
            },
            onError: (err) => {
              toast.error(getErrorMessage(err));
            },
          },
        );
      } catch {
        toast.error(t("editor.invalidJson", "Invalid JSON"));
      }
    },
    [resourceId, currentVersion, cascadeSave, cascadeContext, t],
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
