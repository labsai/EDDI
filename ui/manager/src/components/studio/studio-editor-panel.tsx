import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import { OwningAgentContext } from "@/hooks/use-owning-agent";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { showSavedNotLiveToast } from "@/lib/save-not-live-toast";
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
import { CompatibleVersionCheckbox } from "@/components/agents/compatible-version-checkbox";
import { useAgent } from "@/hooks/use-agents";
import { EDITOR_MAP } from "@/components/editors/editor-registry";
import {
  getExtensionColor,
  getExtensionIcon,
  getExtensionLabel,
  getResourceSlugForExtension,
  getResourceSlugForStep,
} from "@/lib/api/extensions";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/shared/error-state";
import { EmptyState } from "@/components/shared/empty-state";
import { Link } from "react-router-dom";
import { ExternalLink, SquarePen } from "lucide-react";
import { cn } from "@/lib/utils";

// ==================== Types ====================

interface WorkflowStep {
  type: string;
  extensions: Record<string, unknown>;
  config: { uri?: string; [key: string]: unknown };
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
  /** Identifies this panel to the page (the desktop and mobile layouts each mount one). */
  panelId?: string;
  /** Whether this panel has unsaved edits — the page asks before replacing it. */
  onDirtyChange?: (panelId: string, dirty: boolean) => void;
  /**
   * Hands the page a function that saves this panel's unsaved edits and resolves
   * whether it worked, so "Save" in the page's leave-this-stage prompt can keep
   * them. Called with `null` when the panel goes away.
   */
  registerSaver?: (panelId: string, save: (() => Promise<boolean>) | null) => void;
  /**
   * Runs Save & Test: the page saves through `save`, deploys what it created and
   * opens the chat beside the editor on it. Without it there is no such button.
   */
  onSaveAndTest?: (save: () => Promise<{ newAgentVersion: number }>) => void;
  /** A Save & Test is running — disables the buttons. */
  isSaveAndTesting?: boolean;
  /** Viewers see the editor, not its save buttons. */
  readOnly?: boolean;
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
  panelId = "panel",
  onDirtyChange,
  registerSaver,
  onSaveAndTest,
  isSaveAndTesting = false,
  readOnly = false,
}: StudioEditorPanelProps) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  // The same step → editor resolution the workflow page uses: the config
  // URI's store first, the step type as fallback. A private type table here
  // once missed `ai.labs.httpcalls` (the canonical API-call step type) and
  // `ai.labs.langchain`, so those steps opened no editor at all.
  const slug = getResourceSlugForStep(workflowStep) ?? "";
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
  const { data, isLoading, isError, error, refetch } = useResource(
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

  // Every save here writes a new agent version. Unticked by default and reset
  // after each save that wrote one — a stale tick would let running
  // conversations follow a change nobody judged.
  const [compatible, setCompatible] = useState(false);
  // Agents that share a workflow keep this panel mounted when the Studio switches
  // between them: a tick given for one agent's next version must not carry over.
  useEffect(() => {
    setCompatible(false);
  }, [agentId, agentVersion]);
  // Only for its generation: whether ticking has to warn about a legacy version.
  const { data: currentAgent } = useAgent(agentId, agentVersion);

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

  /**
   * Save through the cascade. Rejects with a readable message after adopting the
   * versions a partly-failed cascade already wrote (or every retry 409s).
   */
  const performSave = useCallback(
    async (jsonString: string) => {
      const parsed = JSON.parse(jsonString);
      try {
        const result = await cascadeSave.mutateAsync({
          id: resourceId,
          version: currentVersion,
          body: parsed,
          context: cascadeContext,
          compatible,
        });
        setCompatible(false);
        setSaveSuccess(true);
        setCurrentVersion(result.newResourceVersion);
        onResourceVersionSaved?.(resourceId, result.newResourceVersion);
        // The next save, of this stage or any other, builds on these.
        onCascadeContextChange?.(nextCascadeContext(cascadeContext, result));
        return result;
      } catch (err) {
        const partial = cascadePartialResult(err);
        if (partial?.newResourceVersion !== undefined) {
          setCurrentVersion(partial.newResourceVersion);
          onResourceVersionSaved?.(resourceId, partial.newResourceVersion);
        }
        if (partial?.retryContext) {
          onCascadeContextChange?.(partial.retryContext);
        }
        throw new Error(describeSaveError(err, t), { cause: err });
      }
    },
    [resourceId, currentVersion, cascadeSave, cascadeContext, compatible, onCascadeContextChange, onResourceVersionSaved, t],
  );

  /** Plain save: the agent gets a new version that is NOT what is running. */
  const saveJson = useCallback(
    async (jsonString: string): Promise<boolean> => {
      try {
        const result = await performSave(jsonString);
        showSavedNotLiveToast({
          t,
          queryClient,
          agentId,
          newAgentVersion: result.newAgentVersion,
        });
        return true;
      } catch (err) {
        toast.error(err instanceof SyntaxError ? t("editor.invalidJson", "Invalid JSON") : (err as Error).message);
        return false;
      }
    },
    [performSave, t, queryClient, agentId],
  );

  const handleSave = useCallback((jsonString: string) => void saveJson(jsonString), [saveJson]);

  const handleSaveAndTest = useCallback(
    (jsonString: string) => {
      onSaveAndTest?.(async () => {
        const result = await performSave(jsonString);
        return { newAgentVersion: result.newAgentVersion ?? agentVersion };
      });
    },
    [onSaveAndTest, performSave, agentVersion],
  );

  // The layout reports its working copy; the page asks about it before it
  // replaces this editor with another stage's.
  const draftRef = useRef<{ data: string; dirty: boolean }>({ data: "", dirty: false });
  const busyRef = useRef(false);
  busyRef.current = isSaveAndTesting || cascadeSave.isPending;
  const handleDraftChange = useCallback(
    (draft: { data: string; dirty: boolean }) => {
      const wasDirty = draftRef.current.dirty;
      draftRef.current = draft;
      if (draft.dirty !== wasDirty) onDirtyChange?.(panelId, draft.dirty);
    },
    [onDirtyChange, panelId],
  );
  useEffect(() => {
    registerSaver?.(panelId, async () => {
      const { data: draft, dirty } = draftRef.current;
      if (!dirty) return true;
      // A save or Save & Test is already writing: starting a second would race
      // it on the same versions. Report "not saved" so the page stays put.
      if (busyRef.current) return false;
      return saveJson(draft);
    });
    return () => registerSaver?.(panelId, null);
  }, [registerSaver, panelId, saveJson]);
  useEffect(
    () => () => {
      if (draftRef.current.dirty) onDirtyChange?.(panelId, false);
    },
    [onDirtyChange, panelId],
  );

  // ---- No stored resource behind this step ----
  if (!uri || !rt) {
    return (
      <StudioStepInfo
        workflowStep={workflowStep}
        workflowId={workflowId}
        workflowVersion={workflowVersion}
      />
    );
  }

  // ---- Loading ----
  if (isLoading) {
    return (
      <div className="flex-1 space-y-4 px-6 py-5" data-testid="studio-editor-loading">
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
      <div className="flex-1 px-6 py-5">
        <ErrorState
          error={error}
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
    <div className="flex-1 overflow-y-auto px-6 py-5" data-testid="studio-editor-panel">
      {!readOnly && (
      <CompatibleVersionCheckbox
        checked={compatible}
        onChange={setCompatible}
        disabled={cascadeSave.isPending || isSaveAndTesting}
        previousGeneration={currentAgent ? (currentAgent.compatibilityGeneration ?? null) : undefined}
        className="mb-3"
      />
      )}
      <OwningAgentContext.Provider value={agentId}>
      <ConfigEditorLayout
        typeName={typeName}
        typeIcon={getExtensionIcon(workflowStep.type)}
        resourceId={resourceId}
        data={JSON.stringify(data, null, 2)}
        versions={versions}
        currentVersion={currentVersion}
        onVersionChange={setCurrentVersion}
        onSave={handleSave}
        onSaveAndDeploy={onSaveAndTest && !readOnly ? handleSaveAndTest : undefined}
        isSaveAndDeploying={isSaveAndTesting}
        readOnly={readOnly}
        onDraftChange={handleDraftChange}
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
      </OwningAgentContext.Provider>
    </div>
  );
}

// ==================== Store-less steps ====================

/** Step types that only render templates and have no configuration of their own. */
const TEMPLATING_TYPES = new Set(["eddi://ai.labs.templating", "eddi://ai.labs.output.template"]);

type StepInfoKind = "templating" | "inline" | "noConfig" | "unknown";

/**
 * What the Studio shows for a step with no stored configuration resource
 * behind it, instead of a bare "no editor" warning: what the step is, why there
 * is nothing to edit here, its inline configuration (read-only), and the way to
 * the workflow page, where steps are attached, configured inline and reordered.
 */
function StudioStepInfo({
  workflowStep,
  workflowId,
  workflowVersion,
}: {
  workflowStep: WorkflowStep;
  workflowId: string;
  workflowVersion: number;
}) {
  const { t } = useTranslation();
  const type = workflowStep.type.startsWith("eddi://") ? workflowStep.type : `eddi://${workflowStep.type}`;
  const Icon = getExtensionIcon(type);
  const label = getExtensionLabel(type, t);
  const config: Record<string, unknown> = workflowStep.config ?? {};
  const uri = typeof config.uri === "string" ? config.uri : "";
  const inlineConfig = Object.fromEntries(Object.entries(config).filter(([key]) => key !== "uri"));
  const extensions = workflowStep.extensions ?? {};
  const hasInline = Object.keys(inlineConfig).length > 0 || Object.keys(extensions).length > 0;

  let kind: StepInfoKind;
  if (TEMPLATING_TYPES.has(type)) kind = "templating";
  // A URI whose store and type this Manager has no editor for.
  else if (uri) kind = "unknown";
  else if (hasInline) kind = "inline";
  else if (getResourceSlugForExtension(type)) kind = "noConfig";
  else kind = "unknown";

  const messages: Record<StepInfoKind, string> = {
    templating: t(
      "studio.stepInfo.templating",
      "Renders the {…} placeholders in this turn's output texts and quick replies. It has no configuration of its own, so there is nothing to edit here."
    ),
    inline: t(
      "studio.stepInfo.inlineConfig",
      "This step's configuration is embedded in the workflow rather than stored as a separate resource. Edit it on the workflow page."
    ),
    noConfig: t(
      "studio.stepInfo.noConfig",
      "This step references no configuration resource. Attach one on the workflow page."
    ),
    unknown: t(
      "studio.stepInfo.unknown",
      "The Manager has no editor for this step type. Its configuration as stored in the workflow is shown below."
    ),
  };

  const showJson = hasInline || !!uri;

  return (
    <div className="flex-1 overflow-y-auto px-6 py-5" data-testid="studio-step-info" data-kind={kind}>
      <div className="mx-auto max-w-3xl space-y-4">
        <div className="flex items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-muted">
            <Icon className={cn("h-5 w-5", getExtensionColor(type))} aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="text-lg font-semibold text-foreground">{label}</h2>
            <p className="truncate font-mono text-xs text-muted-foreground" data-testid="studio-step-info-type">
              {workflowStep.type}
            </p>
          </div>
          <Button variant="outline" size="sm" asChild>
            <Link
              to={`/manage/workflowview/${workflowId}?version=${workflowVersion}`}
              data-testid="studio-step-info-open-workflow"
            >
              <ExternalLink className="rtl:-scale-x-100" aria-hidden="true" />
              {t("studio.stepInfo.openWorkflow", "Open workflow")}
            </Link>
          </Button>
        </div>
        <p
          className="rounded-xl border border-border bg-card p-4 text-sm text-muted-foreground"
          data-testid="studio-step-info-message"
        >
          {messages[kind]}
        </p>
        {showJson && (
          <section className="space-y-2">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("studio.stepInfo.configHeading", "Step configuration (read-only)")}
            </h3>
            <pre
              className="max-h-[60vh] overflow-auto rounded-xl border border-border bg-muted/40 p-4 font-mono text-xs text-foreground"
              data-testid="studio-step-info-json"
            >
              {JSON.stringify({ config, extensions }, null, 2)}
            </pre>
          </section>
        )}
      </div>
    </div>
  );
}

// ==================== Empty Placeholder ====================

export function StudioEditorEmpty() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-1 items-center justify-center px-6 py-5" data-testid="studio-editor-empty">
      <div className="w-full max-w-lg">
        <EmptyState
          icon={SquarePen}
          title={t("studio.selectStage", "Click a pipeline stage to open its editor")}
          description={t(
            "studio.selectStageHint",
            "Select any extension in the pipeline to view and edit its configuration inline"
          )}
        />
      </div>
    </div>
  );
}
