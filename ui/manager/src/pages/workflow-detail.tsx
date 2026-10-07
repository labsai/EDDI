import { RequestAccessPanel } from "@/components/workspaces/request-access-panel";
import { isForbidden } from "@/lib/access";
import { useState, useCallback, useMemo, useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useParams, Link, useNavigate, useSearchParams } from "react-router-dom";
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
  Rocket,
  X,
} from "lucide-react";
import { cn, formatRelativeTime } from "@/lib/utils";
import { accessForDetail } from "@/lib/access";
import { useSpaces } from "@/hooks/use-spaces";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";
import { getErrorMessage } from "@/lib/api-client";
import { describeSaveError } from "@/lib/save-error";
import { showSavedNotLiveToast } from "@/lib/save-not-live-toast";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { EditableTitle } from "@/components/shared/editable-title";
import {
  useWorkflow,
  useUpdateWorkflow,
  useUpdateWorkflowDescriptor,
  useDeleteWorkflow,
  useWorkflowVersions,
} from "@/hooks/use-workflows";
import { useAgent } from "@/hooks/use-agents";
import { deleteResource, type ResourceTypeConfig } from "@/lib/api/resources";
import { buildStepResourceLink } from "@/lib/workflow-step-links";

import { parseResourceUri } from "@/lib/api/agents";
import type { WorkflowExtension } from "@/lib/api/workflows";
import { addWorkflowStep } from "@/lib/workflow-steps";
import { parseVersionFromLocation } from "@/lib/api/location-version";
import {
  PipelineBuilder,
  type PipelineItem,
} from "@/components/editors/pipeline-builder";
import {
  AddExtensionDialog,
  type AddExtensionResult,
} from "@/components/editors/add-extension-dialog";
import { useLatestVersions } from "@/hooks/use-latest-versions";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { useSaveAndDeploy } from "@/hooks/use-save-and-deploy";
import { getAgent, updateAgent, type Agent } from "@/lib/api/agents";
import { CompatibleVersionCheckbox } from "@/components/agents/compatible-version-checkbox";
import {
  ParserEditor,
} from "@/components/editors/parser-editor";
import {
  createDefaultParserData,
  type ParserData,
} from "@/components/editors/parser-editor-types";

/** A resource created by "Add Task → Create new" during this edit. */
interface CreatedResource {
  resourceType: ResourceTypeConfig;
  id: string;
  version: number;
  name: string;
}

function parseVersionParam(raw: string | null): number | undefined {
  if (!raw) return undefined;
  const n = parseInt(raw, 10);
  return Number.isSafeInteger(n) && n > 0 ? n : undefined;
}

/** The versions of `workflowId` that an agent's workflow references name. */
function pinnedWorkflowVersions(agent: Agent | undefined, workflowId: string): number[] {
  const out: number[] = [];
  for (const uri of agent?.workflows ?? []) {
    try {
      const ref = parseResourceUri(uri);
      if (ref.id === workflowId) out.push(ref.version);
    } catch {
      // an unparseable reference cannot be this workflow
    }
  }
  return out;
}

/** Thrown before anything is written when the agent does not reference what is being saved. */
class AgentReferenceError extends Error {}

/* ─── Main page ─── */
export function WorkflowDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  // Cascade context from URL (when navigating from agent-detail)
  const agentId = searchParams.get("agentId") ?? undefined;
  const agentVer = searchParams.get("agentVer") ?? undefined;

  // The workflow version the link named. From an agent that pins an older
  // workflow this is that version, not the newest: opening the latest instead
  // made every save here unable to change the agent.
  const urlVersion = parseVersionParam(searchParams.get("version"));
  const [version, setVersion] = useState<number | undefined>(urlVersion);
  const [currentAgentVer, setCurrentAgentVer] = useState<number | undefined>(
    agentVer ? parseInt(agentVer, 10) : undefined
  );
  const [showAddDialog, setShowAddDialog] = useState(false);
  // Save & Test writes a new agent version: breaking unless the user ticks
  // this. Reset after each such save — every save is its own decision.
  const [agentCompatible, setAgentCompatible] = useState(false);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);
  // Resources created through "Add Task → Create new" in this edit. They exist
  // on the server although the steps that reference them do not yet.
  const [createdResources, setCreatedResources] = useState<CreatedResource[]>([]);
  const [deleteCreatedOnDiscard, setDeleteCreatedOnDiscard] = useState(true);
  const [localExtensions, setLocalExtensions] = useState<
    WorkflowExtension[] | null
  >(null);
  const [saveMessage, setSaveMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  // Parser inline editing
  const [parserEditIndex, setParserEditIndex] = useState<number | null>(null);
  const [parserEditData, setParserEditData] = useState<ParserData | null>(null);

  const queryClient = useQueryClient();
  const renameMutation = useUpdateWorkflowDescriptor();
  const { data: versionDescriptors } = useWorkflowVersions(id!);
  // Deleting a workflow needs OWN; an EDIT grantee may still change it.
  // Only consulted when no descriptor for this id came back — see accessForDetail.
  // `enforcement`, not `enabled`: a failed /workspaces must not read as "off".
  const workspacesEnforced = useSpaces().enforcement;
  const access = accessForDetail(versionDescriptors, id, workspacesEnforced);
  // A viewer gets the pipeline to look at, not to edit.
  const readOnly = !access.canEdit;

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

  // Default to latest version
  const resolvedVersion = version ?? versions[0]?.version ?? 1;

  const {
    data: workflow,
    isLoading,
    isError,
    error: workflowError,
    refetch,
  } = useWorkflow(id!, resolvedVersion);
  const updateMutation = useUpdateWorkflow();
  const deleteMutation = useDeleteWorkflow();

  // The agent this page was opened from, to see whether it references THIS
  // workflow version — see `agentMismatch` below.
  const { data: contextAgent } = useAgent(agentId ?? "", currentAgentVer);
  const pinnedVersions = useMemo(
    () => (agentId && contextAgent ? pinnedWorkflowVersions(contextAgent, id!) : null),
    [agentId, contextAgent, id],
  );
  // The agent pins a different version of this workflow than the one on screen:
  // a save here would not change the agent (and Save & Test used to deploy an
  // unchanged agent version without saying so).
  const agentMismatch =
    pinnedVersions !== null && pinnedVersions.length > 0 && !pinnedVersions.includes(resolvedVersion);

  // Use local state if user has made edits, otherwise use server data
  const currentExtensions = useMemo(
    () => localExtensions ?? workflow?.workflowSteps ?? [],
    [localExtensions, workflow?.workflowSteps]
  );
  const serverExtensions = useMemo(() => workflow?.workflowSteps ?? [], [workflow?.workflowSteps]);
  const isDirty =
    localExtensions !== null &&
    JSON.stringify(localExtensions) !== JSON.stringify(serverExtensions);

  // Warn on tab close/reload when dirty
  useUnsavedChangesGuard(isDirty);

  // Save & Test support
  const { saveAndDeploy, isRunning: isSaveAndDeploying } = useSaveAndDeploy();

  // Build pipeline items from current extensions
  const pipelineItems: PipelineItem[] = currentExtensions.map((ext, i) => ({
    id: `ext-${i}`,
    index: i,
    extension: ext,
  }));

  // Collect all config URIs for version staleness detection
  const configUris = useMemo(
    () =>
      currentExtensions
        .map((ext) => (ext.config?.uri as string) ?? "")
        .filter((uri) => uri.includes("://")),
    [currentExtensions]
  );
  const { data: latestVersions } = useLatestVersions(configUris);

  // What the SAVED workflow references — a step outside it cannot be edited yet.
  const savedStepUris = useMemo(
    () =>
      serverExtensions
        .map((ext) => ext.config?.uri)
        .filter((uri): uri is string => typeof uri === "string" && uri.includes("://")),
    [serverExtensions],
  );

  // Reset local state when server data changes (version switch)
  useEffect(() => {
    setLocalExtensions(null);
  }, [workflow?.workflowSteps]);

  // The page stays mounted when only the query string changes, so the agent
  // context can switch underneath it. A compatibility tick is about ONE agent's
  // next version and must not carry over to another agent's; the version the
  // next Save & Test replaces comes from the new context too.
  useEffect(() => {
    setAgentCompatible(false);
    setCurrentAgentVer(agentVer ? parseInt(agentVer, 10) : undefined);
  }, [agentId, agentVer]);

  // Same for the workflow version a link names, and for a different workflow.
  useEffect(() => {
    setVersion(urlVersion);
  }, [urlVersion, id]);

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
    (result: AddExtensionResult) => {
      // Parser steps get default inline config instead of an empty config
      const isParser = result.descriptor.type === "eddi://ai.labs.parser";
      const defaultParser = isParser ? createDefaultParserData() : undefined;

      const newExt: WorkflowExtension = {
        type: result.descriptor.type,
        extensions: isParser && !result.configUri
          ? ({ ...defaultParser!.extensions } as Record<string, unknown>)
          : {},
        config: result.configUri
          ? { uri: result.configUri }
          : isParser
            ? ({ ...defaultParser!.config } as Record<string, unknown>)
            : {},
      };
      setLocalExtensions(addWorkflowStep(currentExtensions, newExt));
      if (result.created) {
        const created = result.created;
        setCreatedResources((prev) => [...prev, created]);
      }
      setShowAddDialog(false);
    },
    [currentExtensions]
  );

  const handleUpdateVersion = useCallback(
    (index: number, newUri: string) => {
      const updated = [...currentExtensions];
      const ext = updated[index];
      if (ext) {
        updated[index] = {
          ...ext,
          config: { ...ext.config, uri: newUri },
        };
        setLocalExtensions(updated);
      }
    },
    [currentExtensions]
  );


  /**
   * Write the edited steps as a new workflow version and, when this page was
   * opened from an agent, point that agent at it.
   *
   * The agent is read and checked BEFORE anything is written. Replacing a
   * reference that is not there used to be a silent no-op: the agent was re-saved
   * unchanged as a new version, reported as saved and — by Save & Test —
   * deployed, without the edit.
   */
  const persist = useCallback(async (): Promise<{
    newWorkflowVersion: number;
    newAgentVersion?: number;
  }> => {
    if (!localExtensions) throw new Error("Nothing to save");
    let agent: Agent | undefined;
    let refIndex = -1;
    if (agentId && currentAgentVer) {
      agent = await getAgent(agentId, currentAgentVer);
      refIndex = (agent.workflows ?? []).findIndex((u) => {
        try {
          const ref = parseResourceUri(u);
          return ref.id === id && ref.version === resolvedVersion;
        } catch {
          return false;
        }
      });
      if (refIndex < 0) {
        const found = pinnedWorkflowVersions(agent, id!);
        throw new AgentReferenceError(
          found.length > 0
            ? t(
                "packageEditor.agentPinsOtherVersion",
                "Agent version {{agentVer}} uses this workflow at version {{found}}, not version {{version}}, so saving here would not change the agent. Nothing was saved. Open version {{found}}, or point the agent at version {{version}}.",
                { agentVer: currentAgentVer, found: found.join(", "), version: resolvedVersion },
              )
            : t(
                "packageEditor.agentMissingWorkflow",
                "Agent version {{agentVer}} does not use this workflow, so saving here would not change the agent. Nothing was saved.",
                { agentVer: currentAgentVer },
              ),
        );
      }
    }

    const wfResult = await updateMutation.mutateAsync({
      id: id!,
      version: resolvedVersion,
      config: { workflowSteps: localExtensions },
    });
    const newWorkflowVersion = parseVersionFromLocation(wfResult.location);
    if (newWorkflowVersion === null) {
      throw new Error(t("packageEditor.noVersionReturned", "The server did not report the new workflow version."));
    }
    // Track the new version so subsequent saves target the correct version
    setVersion(newWorkflowVersion);
    setLocalExtensions(null);
    setCreatedResources([]);

    if (!agent || !agentId || !currentAgentVer) return { newWorkflowVersion };

    const newWfUri = `eddi://ai.labs.workflow/workflowstore/workflows/${id}?version=${newWorkflowVersion}`;
    const updatedAgent: Agent = {
      ...agent,
      workflows: (agent.workflows ?? []).map((u, i) => (i === refIndex ? newWfUri : u)),
    };
    try {
      const agentResult = agentCompatible
        ? await updateAgent(agentId, currentAgentVer, updatedAgent, { compatible: true })
        : await updateAgent(agentId, currentAgentVer, updatedAgent);
      const newAgentVersion = parseVersionFromLocation(agentResult.location);
      if (newAgentVersion === null) {
        throw new Error(t("packageEditor.noVersionReturned", "The server did not report the new workflow version."));
      }
      setAgentCompatible(false);
      setCurrentAgentVer(newAgentVersion);
      queryClient.invalidateQueries({ queryKey: ["agents"] });
      return { newWorkflowVersion, newAgentVersion };
    } catch (err) {
      // The workflow is written; only the agent still points at the old one.
      // The mismatch banner offers the way forward.
      throw new AgentReferenceError(
        t(
          "packageEditor.agentUpdateFailed",
          "Workflow saved as version {{version}}, but updating the agent failed: {{error}}",
          { version: newWorkflowVersion, error: getErrorMessage(err) },
        ),
      );
    }
  }, [localExtensions, agentId, currentAgentVer, id, resolvedVersion, updateMutation, agentCompatible, queryClient, t]);

  const reportSaveError = useCallback(
    (err: unknown) => {
      const text = err instanceof AgentReferenceError ? err.message : describeSaveError(err, t);
      setSaveMessage({ type: "error", text });
      toast.error(text);
    },
    [t],
  );

  const handleSave = useCallback(async () => {
    if (!isDirty || !localExtensions || readOnly) return;
    try {
      const result = await persist();
      if (agentId && result.newAgentVersion !== undefined) {
        showSavedNotLiveToast({ t, queryClient, agentId, newAgentVersion: result.newAgentVersion });
      }
      setSaveMessage({
        type: "success",
        text: t("packageEditor.saved", "Workflow saved successfully"),
      });
    } catch (err) {
      reportSaveError(err);
    }
  }, [isDirty, localExtensions, readOnly, persist, agentId, queryClient, reportSaveError, t]);

  const handleSaveAndDeploy = useCallback(async () => {
    if (!isDirty || !localExtensions || !agentId || !currentAgentVer || readOnly) return;

    await saveAndDeploy({
      agentId,
      save: async () => {
        try {
          const { newAgentVersion } = await persist();
          if (newAgentVersion === undefined) throw new Error("The agent was not updated");
          return { newAgentVersion };
        } catch (err) {
          // Save & Test shows the error's message as it stands.
          throw new Error(err instanceof AgentReferenceError ? err.message : describeSaveError(err, t), { cause: err });
        }
      },
    });
  }, [isDirty, localExtensions, agentId, currentAgentVer, readOnly, saveAndDeploy, persist, t]);

  // An unsaved step cannot be edited: its resource is not in the saved workflow,
  // so the resource editor could not cascade into it. Save first, then open it.
  const handleSaveAndEdit = useCallback(
    async (index: number) => {
      const uri = currentExtensions[index]?.config?.uri;
      if (typeof uri !== "string" || readOnly) return;
      try {
        const result = await persist();
        const link = buildStepResourceLink(uri, {
          workflowId: id,
          workflowVersion: result.newWorkflowVersion,
          agentId,
          agentVer:
            agentId !== undefined
              ? String(result.newAgentVersion ?? currentAgentVer ?? agentVer ?? "")
              : undefined,
        });
        if (link) navigate(link);
      } catch (err) {
        reportSaveError(err);
      }
    },
    [currentExtensions, readOnly, persist, id, agentId, currentAgentVer, agentVer, navigate, reportSaveError],
  );

  // The agent pins another version of this workflow than the one on screen:
  // repoint it, as a new (not yet live) agent version.
  const handlePointAgent = useCallback(async () => {
    if (!agentId || !currentAgentVer) return;
    try {
      const agent = await getAgent(agentId, currentAgentVer);
      const newWfUri = `eddi://ai.labs.workflow/workflowstore/workflows/${id}?version=${resolvedVersion}`;
      let replaced = false;
      const updatedAgent: Agent = {
        ...agent,
        workflows: (agent.workflows ?? []).map((u) => {
          if (replaced) return u;
          try {
            if (parseResourceUri(u).id !== id) return u;
          } catch {
            return u;
          }
          replaced = true;
          return newWfUri;
        }),
      };
      // Nothing to repoint: do not write an unchanged agent version and call it saved.
      if (!replaced) {
        toast.error(
          t(
            "packageEditor.agentMissingWorkflow",
            "Agent version {{agentVer}} does not use this workflow, so saving here would not change the agent. Nothing was saved.",
            { agentVer: currentAgentVer },
          ),
        );
        return;
      }
      const result = agentCompatible
        ? await updateAgent(agentId, currentAgentVer, updatedAgent, { compatible: true })
        : await updateAgent(agentId, currentAgentVer, updatedAgent);
      const newAgentVersion = parseVersionFromLocation(result.location);
      setAgentCompatible(false);
      if (newAgentVersion !== null) setCurrentAgentVer(newAgentVersion);
      queryClient.invalidateQueries({ queryKey: ["agents"] });
      showSavedNotLiveToast({ t, queryClient, agentId, newAgentVersion });
    } catch (err) {
      toast.error(getErrorMessage(err));
    }
  }, [agentId, currentAgentVer, id, resolvedVersion, agentCompatible, queryClient, t]);

  // Discarding is destructive and used to be one click.
  const handleDiscard = useCallback(() => {
    setDeleteCreatedOnDiscard(true);
    setShowDiscardConfirm(true);
  }, []);

  const confirmDiscard = useCallback(async () => {
    setShowDiscardConfirm(false);
    setLocalExtensions(null);
    const created = createdResources;
    setCreatedResources([]);
    if (!deleteCreatedOnDiscard || created.length === 0) return;
    const results = await Promise.allSettled(
      created.map((c) => deleteResource(c.resourceType, c.id, c.version)),
    );
    const failed = results.filter((r) => r.status === "rejected").length;
    if (failed > 0) {
      toast.warning(
        t("packageEditor.createdDeleteFailed", {
          count: failed,
          defaultValue: "{{count}} config created during this edit could not be deleted",
          defaultValue_other: "{{count}} configs created during this edit could not be deleted",
        }),
      );
    }
  }, [createdResources, deleteCreatedOnDiscard, t]);

  // Ctrl/Cmd+S saves, as in the config editors.
  useEffect(() => {
    if (readOnly) return;
    function onKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (isDirty && !updateMutation.isPending && !isSaveAndDeploying) void handleSave();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [readOnly, isDirty, updateMutation.isPending, isSaveAndDeploying, handleSave]);

  // Parser inline editing handlers
  const handleEditInline = useCallback(
    (index: number) => {
      const ext = currentExtensions[index];
      if (ext) {
        setParserEditIndex(index);
        setParserEditData({
          config: (ext.config ?? {}) as ParserData["config"],
          extensions: (ext.extensions ?? {}) as ParserData["extensions"],
        });
      }
    },
    [currentExtensions]
  );

  const handleParserSave = useCallback(() => {
    if (parserEditIndex === null || !parserEditData) return;
    const updated = [...currentExtensions];
    const ext = updated[parserEditIndex];
    if (ext) {
      updated[parserEditIndex] = {
        ...ext,
        config: (parserEditData.config ?? {}) as Record<string, unknown>,
        extensions: (parserEditData.extensions ?? {}) as Record<string, unknown>,
      };
      setLocalExtensions(updated);
    }
    setParserEditIndex(null);
    setParserEditData(null);
  }, [parserEditIndex, parserEditData, currentExtensions]);

  const handleParserCancel = useCallback(() => {
    setParserEditIndex(null);
    setParserEditData(null);
  }, []);

  function handleDelete() {
    deleteMutation.mutate(
      { id: id!, version: resolvedVersion },
      {
        onSuccess: () => {
          toast.success(t("common.delete") + " \u2713");
          setShowDeleteDialog(false);
          navigate("/manage/workflows");
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  const handleVersionChange = useCallback((v: number) => {
    setVersion(v);
    setLocalExtensions(null);
  }, []);

  /* ─── Loading / Error states ─── */
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20" data-testid="workflow-loading">
        <RefreshCw className="h-8 w-8 animate-spin text-primary" />
      </div>
    );
  }

  if (isForbidden(workflowError)) {
    return (
      <div className="space-y-4">
        <BackLink />
        <RequestAccessPanel resourceId={id!} />
      </div>
    );
  }

  if (isError || !workflow) {
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

  const currentDescriptor = versionDescriptors?.find((d) => {
    const match = d.resource?.match(/\?version=(\d+)/);
    return match ? parseInt(match[1]!, 10) === resolvedVersion : false;
  });

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="space-y-2">
          <BackLink />
          <div className="flex items-center gap-3">
            <Workflow className="h-8 w-8 text-primary" />
            <div>
              <EditableTitle
                name={currentDescriptor?.name}
                description={currentDescriptor?.description}
                fallback={t("packageEditor.title", "Workflow Editor")}
                canEdit={access.canEdit}
                onSave={({ name, description }) =>
                  renameMutation.mutateAsync({ id: id!, version: resolvedVersion, name, description })
                }
                data-testid="workflow-title"
              />
              <p className="font-mono text-sm text-muted-foreground">
                {id}
                <span className="ms-2 inline-flex items-center rounded-md bg-primary/10 px-1.5 py-0.5 text-xs font-semibold text-primary">
                  v{resolvedVersion}
                </span>
              </p>
            </div>
          </div>

          {/* Version picker */}
          {versions.length > 0 && (
            <VersionSelect
              versions={versions}
              current={resolvedVersion}
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
          {!readOnly && (
          <button
            onClick={handleDiscard}
            disabled={!isDirty || updateMutation.isPending}
            className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-2 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary active:scale-[0.98] disabled:opacity-50"
            data-testid="discard-btn"
          >
            <Undo2 className="h-4 w-4" />
            {t("editor.discard", "Discard")}
          </button>
          )}

          {/* Save */}
          {!readOnly && (
          <button
            onClick={handleSave}
            disabled={!isDirty || updateMutation.isPending || isSaveAndDeploying}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.98] disabled:opacity-50"
            data-testid="save-btn"
          >
            <Save className="h-4 w-4" />
            {updateMutation.isPending
              ? t("editor.saving", "Saving...")
              : t("editor.save", "Save")}
          </button>
          )}

          {/* Save & Test */}
          {!readOnly && agentId && agentVer && (
            <button
              onClick={handleSaveAndDeploy}
              disabled={!isDirty || updateMutation.isPending || isSaveAndDeploying}
              className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-sm font-medium text-white shadow-sm transition-all hover:bg-emerald-700 active:scale-[0.98] disabled:opacity-50 dark:bg-emerald-600 dark:hover:bg-emerald-700"
              data-testid="save-test-btn"
            >
              <Rocket className="h-4 w-4" />
              {isSaveAndDeploying
                ? t("editor.deploying", "Deploying…")
                : t("editor.saveAndTest", "Save & Test")}
            </button>
          )}

          {/* Delete — OWN only, as on the workflows list */}
          {access.canOwn && (
            <button
              onClick={() => setShowDeleteDialog(true)}
              className="rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
              data-testid="delete-wf-btn"
              aria-label={t("packages.confirmDelete", "Delete Workflow")}
              title={t("packages.confirmDelete", "Delete Workflow")}
            >
              <Trash2 className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>

      {/* The agent this page was opened from pins another version of this workflow */}
      {!readOnly && agentMismatch && pinnedVersions && (
        <div
          className="flex flex-col gap-3 rounded-lg border border-warning/30 bg-warning/5 px-4 py-3 sm:flex-row sm:items-center"
          role="alert"
          data-testid="agent-pin-mismatch"
        >
          <AlertCircle className="h-5 w-5 shrink-0 text-warning" aria-hidden="true" />
          <p className="flex-1 text-sm text-foreground">
            {t(
              "packageEditor.agentPinMismatch",
              "Agent version {{agentVer}} uses this workflow at version {{found}}. You are viewing version {{version}}, so saving here would not change the agent.",
              { agentVer: currentAgentVer, found: pinnedVersions.join(", "), version: resolvedVersion },
            )}
          </p>
          <div className="flex shrink-0 gap-2">
            <button
              type="button"
              onClick={() => handleVersionChange(pinnedVersions[0]!)}
              disabled={isDirty}
              className="rounded-lg border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary disabled:opacity-50"
              data-testid="agent-pin-switch"
            >
              {t("packageEditor.switchToPinned", "Open version {{version}}", { version: pinnedVersions[0] })}
            </button>
            <button
              type="button"
              onClick={() => void handlePointAgent()}
              disabled={isDirty}
              className="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              data-testid="agent-pin-update"
            >
              {t("packageEditor.pointAgentAt", "Point agent at version {{version}}", { version: resolvedVersion })}
            </button>
          </div>
        </div>
      )}

      {/* Save & Test also writes a new version of the agent */}
      {!readOnly && agentId && agentVer && (
        <CompatibleVersionCheckbox
          checked={agentCompatible}
          onChange={setAgentCompatible}
          disabled={updateMutation.isPending || isSaveAndDeploying}
          className="max-w-2xl"
          data-testid="save-test-compatible-checkbox"
        />
      )}

      {/* Pipeline section */}
      <section className="rounded-xl border bg-card shadow-sm">
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            <Workflow className="h-5 w-5 text-primary" />
            <h2 className="text-lg font-semibold text-foreground">
              {t("packageEditor.pipeline", "Pipeline")}
            </h2>
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary" data-testid="pipeline-step-count">
              {currentExtensions.length}
            </span>
          </div>
          {!readOnly && (
          <button
            onClick={() => setShowAddDialog(true)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary/10 px-3 py-1.5 text-sm font-medium text-primary hover:bg-primary/20 transition-colors"
            data-testid="add-extension-btn"
          >
            <Plus className="h-4 w-4" />
            {t("packageEditor.addTask", "Add Task")}
          </button>
          )}

        </div>

        <PipelineBuilder
          items={pipelineItems}
          onChange={handleReorder}
          onRemove={handleRemoveExtension}
          disabled={updateMutation.isPending || readOnly}
          workflowId={id}
          workflowVersion={resolvedVersion}
          agentId={agentId}
          agentVer={currentAgentVer !== undefined ? String(currentAgentVer) : agentVer}
          latestVersions={latestVersions}
          onUpdateVersion={readOnly ? undefined : handleUpdateVersion}
          onEditInline={readOnly ? undefined : handleEditInline}
          savedStepUris={savedStepUris}
          onSaveAndEdit={readOnly ? undefined : handleSaveAndEdit}
        />
      </section>

      {/* Add extension dialog */}
      <AddExtensionDialog
        open={showAddDialog}
        onClose={() => setShowAddDialog(false)}
        onSelect={handleAddExtension}
      />


      {/* Parser inline editing dialog */}
      {parserEditIndex !== null && parserEditData && (
        <ParserDialog
          data={parserEditData}
          onChange={setParserEditData}
          onSave={handleParserSave}
          onCancel={handleParserCancel}
        />
      )}

      {/* Raw config (collapsible) */}
      <RawConfigSection config={workflow} />

      {/* Discard confirmation */}
      <UnsavedChangesDialog
        open={showDiscardConfirm}
        onConfirm={() => void confirmDiscard()}
        onCancel={() => setShowDiscardConfirm(false)}
        title={t("editor.discardTitle", "Discard Changes?")}
        message={t(
          "editor.discardMessage",
          "Are you sure you want to discard all unsaved changes? This action cannot be undone."
        )}
      >
        {createdResources.length > 0 && (
          <label className="mt-3 flex cursor-pointer items-start gap-2 text-sm text-foreground">
            <input
              type="checkbox"
              className="mt-0.5 h-4 w-4"
              checked={deleteCreatedOnDiscard}
              onChange={(e) => setDeleteCreatedOnDiscard(e.target.checked)}
              data-testid="discard-delete-created"
            />
            <span>
              {t("packageEditor.discardDeleteCreated", {
                count: createdResources.length,
                defaultValue: "Also delete the {{count}} config created during this edit",
                defaultValue_other: "Also delete the {{count}} configs created during this edit",
              })}
            </span>
          </label>
        )}
      </UnsavedChangesDialog>

      {/* Delete confirmation dialog */}
      <AlertDialog
        open={showDeleteDialog}
        onOpenChange={setShowDeleteDialog}
        title={t("packages.confirmDelete", "Delete Workflow")}
        description={t("packages.confirmDeleteDescription", "This action cannot be undone. The workflow and all its data will be permanently removed.")}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={handleDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}

/* ─── Sub-components ─── */

function BackLink() {
  const { t } = useTranslation();
  const [searchParams] = useSearchParams();
  const parentAgentId = searchParams.get("agentId");

  if (parentAgentId) {
    return (
      <Link
        to={`/manage/agentview/${parentAgentId}`}
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
      >
        <ArrowLeft className="h-4 w-4" />
        {t("packageDetail.backToAgent", "Back to Agent")}
      </Link>
    );
  }

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
  const { t } = useTranslation();
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
      aria-label={t("editor.versionPicker", "Select version")}
      title={disabled ? t("editor.versionLockedDirty", "Save or discard your changes to switch versions") : undefined}
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

// formatRelativeTime imported from @/lib/utils

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
        aria-expanded={expanded}
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

/* ─── Parser Editing Dialog ─── */

function ParserDialog({
  data,
  onChange,
  onSave,
  onCancel,
}: {
  data: ParserData;
  onChange: (d: ParserData) => void;
  onSave: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const dialogRef = useRef<HTMLDivElement>(null);

  // Auto-focus the dialog panel on mount for keyboard a11y
  useEffect(() => {
    dialogRef.current?.focus();
  }, []);

  // Lock body scroll while dialog is open
  useEffect(() => {
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = prev;
    };
  }, []);

  // Close on Escape key
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        e.stopPropagation();
        onCancel();
      }
    },
    [onCancel],
  );

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      data-testid="parser-edit-dialog"
      onKeyDown={handleKeyDown}
    >
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm animate-in fade-in duration-150"
        onClick={onCancel}
        aria-hidden="true"
      />

      {/* Dialog panel */}
      <div
        ref={dialogRef}
        tabIndex={-1}
        className="relative z-10 w-full max-w-lg max-h-[85vh] overflow-y-auto rounded-xl border border-border bg-card shadow-2xl mx-4 p-5 outline-none animate-in fade-in zoom-in-95 duration-200"
        role="dialog"
        aria-modal="true"
        aria-labelledby="parser-dialog-title"
      >
        {/* Header */}
        <div className="flex items-center justify-between mb-4">
          <h3 id="parser-dialog-title" className="text-lg font-semibold text-foreground">
            {t("parserEditor.title", "Parser Configuration")}
          </h3>
          <button
            onClick={onCancel}
            className="rounded-md p-1.5 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label={t("common.close", "Close")}
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* Editor content */}
        <ParserEditor data={data} onChange={onChange} />

        {/* Footer */}
        <div className="mt-4 flex items-center justify-end gap-2 border-t border-border pt-4">
          <button
            onClick={onCancel}
            className="inline-flex items-center gap-1.5 rounded-lg border border-input px-4 py-2 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            data-testid="parser-dialog-cancel"
          >
            {t("common.cancel", "Cancel")}
          </button>
          <button
            onClick={onSave}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            data-testid="parser-dialog-save"
          >
            {t("common.apply", "Apply")}
          </button>
        </div>
      </div>
    </div>
  );
}
