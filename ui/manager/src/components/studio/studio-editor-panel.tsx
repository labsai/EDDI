import { useState, useCallback, useMemo, type ReactNode } from "react";
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
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/shared/error-state";
import { Layers, AlertTriangle } from "lucide-react";
import {
  RulesEditor,
  type RulesConfig,
} from "@/components/editors/rules-editor";
import {
  LlmEditor,
  type LlmConfig,
} from "@/components/editors/llm-editor";
import {
  ApiCallsEditor,
  type HttpCallsConfig as ApiCallsConfig,
} from "@/components/editors/apicalls-editor";
import {
  OutputEditor,
  type OutputConfig,
} from "@/components/editors/output-editor";
import {
  PropertySetterEditor,
  type PropertySetterConfig,
} from "@/components/editors/propertysetter-editor";
import {
  DictionaryEditor,
  type DictionaryConfig,
} from "@/components/editors/dictionary-editor";
import {
  McpCallsEditor,
  type McpCallsConfig,
} from "@/components/editors/mcpcalls-editor";
import {
  RagEditor,
  type RagConfig,
} from "@/components/editors/rag-editor";
import {
  SnippetEditor,
  type PromptSnippetConfig,
} from "@/components/editors/snippet-editor";

// ==================== Editor Map ====================

const EDITOR_MAP: Record<
  string,
  (parsed: unknown, onChange: (val: unknown) => void, readOnly: boolean, meta: { resourceId: string; version: number }) => ReactNode
> = {
  rules: (p, o, r) => (
    <RulesEditor data={p as RulesConfig} onChange={o} readOnly={r} />
  ),
  apicalls: (p, o, r) => (
    <ApiCallsEditor data={p as ApiCallsConfig} onChange={o} readOnly={r} />
  ),
  llm: (p, o, r) => (
    <LlmEditor data={p as LlmConfig} onChange={o} readOnly={r} />
  ),
  output: (p, o, r) => (
    <OutputEditor data={p as OutputConfig} onChange={o} readOnly={r} />
  ),
  propertysetter: (p, o, r) => (
    <PropertySetterEditor data={p as PropertySetterConfig} onChange={o} readOnly={r} />
  ),
  dictionary: (p, o, r) => (
    <DictionaryEditor data={p as DictionaryConfig} onChange={o} readOnly={r} />
  ),
  mcpcalls: (p, o, r) => (
    <McpCallsEditor data={p as McpCallsConfig} onChange={o} readOnly={r} />
  ),
  rag: (p, o, r, meta) => (
    <RagEditor data={p as RagConfig} onChange={o} readOnly={r} resourceId={meta.resourceId} version={meta.version} />
  ),
  snippets: (p, o, r) => (
    <SnippetEditor data={p as PromptSnippetConfig} onChange={o} readOnly={r} />
  ),
};

// ==================== Extension → Resource Slug Map ====================

const EXTENSION_TO_SLUG: Record<string, string> = {
  "ai.labs.rules": "rules",
  "ai.labs.apicalls": "apicalls",
  "ai.labs.llm": "llm",
  "ai.labs.output": "output",
  "ai.labs.output.template": "output",
  "ai.labs.property": "propertysetter",
  "ai.labs.mcpcalls": "mcpcalls",
  "ai.labs.dictionary": "dictionary",
  "ai.labs.parser": "dictionary",
  "ai.labs.rag": "rag",
  "ai.labs.snippets": "snippets",
};

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
  useMemo(() => {
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
  const cascadeContext = useMemo(() => ({
    workflowId,
    packageVersion: workflowVersion,
    agentId,
    agentVersion,
  }), [workflowId, workflowVersion, agentId, agentVersion]);

  const handleSave = useCallback(
    async (jsonString: string) => {
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
              setCurrentVersion(result.newResourceVersion);
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
