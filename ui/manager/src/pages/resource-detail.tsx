import { useState, useCallback, useMemo, useEffect } from "react";
import { useParams, Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import {
  FileCode,
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
  Plug,
  Trash2,
  Copy,
  Puzzle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ErrorState } from "@/components/shared/error-state";
import { BackLink } from "@/components/shared/back-link";
import { getResourceType } from "@/lib/api/resources";
import {
  useResource,
  useResourceVersions,
  useDeleteResource,
  useDuplicateResource,
  useCascadeSave,
} from "@/hooks/use-resources";
import { useNavigate } from "react-router-dom";
import type { LucideIcon } from "lucide-react";
import { ConfigEditorLayout } from "@/components/editors/config-editor-layout";
import { EDITOR_MAP } from "@/components/editors/editor-registry";
import { UpdateUsageDialog } from "@/components/editors/update-usage-dialog";
import {
  findResourceUsage,
  type ResourceUsage,
} from "@/lib/api/resource-usage";
import { useJsonSchema } from "@/hooks/use-json-schema";
import type { CascadeContext } from "@/lib/api/cascade-save";
import {
  CascadeReferenceError,
  cascadePartialResult,
  cascadeVersionUpdate,
  nextCascadeContext,
} from "@/lib/api/cascade-save";
import { describeSaveError } from "@/lib/save-error";
import { VersionDiffDialog } from "@/components/editors/version-diff-dialog";
import { getResource } from "@/lib/api/resources";
import { useAgentContext } from "@/hooks/use-agent-context";
import { useSaveAndDeploy } from "@/hooks/use-save-and-deploy";
import { deployAgent } from "@/lib/api/agents";

const ICON_MAP: Record<string, LucideIcon> = {
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  BookOpenCheck: BookOpen, // reuse BookOpen for Knowledge Bases
  Brain,
  Settings,
  Plug,
  Puzzle,
};


/**
 * One id for the "saved — not yet live" toast, so only the most recent save's
 * Deploy action is ever on screen. See where it is used for why that matters.
 */
const SAVE_NOT_LIVE_TOAST_ID = "resource-save-not-live";

export function ResourceDetailPage() {
  const { type, id } = useParams<{ type: string; id: string }>();
  const [searchParams] = useSearchParams();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const rt = getResourceType(type ?? "");
  const Icon = ICON_MAP[rt?.icon ?? ""] ?? FileCode;
  const typeName = rt ? t(`${rt.labelKey}.name`) : type ?? "";

  // Cascade context from URL search params (set when navigating from agent/workflow)
  // Track in state so versions update after each cascade save
  const initialCascade = useMemo(() => {
    const wfId = searchParams.get("wfId");
    const wfVer = searchParams.get("wfVer");
    const agentId = searchParams.get("agentId");
    const agentVer = searchParams.get("agentVer");
    if (wfId && wfVer && agentId && agentVer) {
      return {
        workflowId: wfId,
        workflowVersion: parseInt(wfVer, 10),
        agentId: agentId,
        agentVersion: parseInt(agentVer, 10),
      };
    }
    return undefined;
  }, [searchParams]);

  const [cascadeContext, setCascadeContext] = useState<CascadeContext | undefined>(initialCascade);

  // Sync when URL params change (user navigates to a different resource)
  useEffect(() => {
    setCascadeContext(initialCascade);
  }, [initialCascade]);

  // Version state — default to latest version once descriptors are loaded
  const [currentVersion, setCurrentVersion] = useState<number | undefined>(undefined);

  // Reset version when navigating to a different resource (React reuses
  // the component for same-type routes, so useState values persist).
  useEffect(() => {
    setCurrentVersion(undefined);
  }, [id, type]);

  // Fetch version descriptors first — needed to resolve the latest version
  const {
    data: versionDescriptors,
    isLoading: isVersionsLoading,
    isError: isVersionsError,
  } = useResourceVersions(type ?? "", id ?? "");

  // Resolve latest version from descriptors
  useEffect(() => {
    if (currentVersion === undefined && versionDescriptors) {
      if (versionDescriptors.length > 0) {
        const latest = versionDescriptors.reduce((max, d) => {
          const match = d.resource?.match(/\?version=(\d+)/);
          const v = match ? parseInt(match[1] ?? "1", 10) : 1;
          return v > max ? v : max;
        }, 1);
        setCurrentVersion(latest);
      } else {
        // Descriptors loaded but empty — default to version 1
        setCurrentVersion(1);
      }
    }
  }, [currentVersion, versionDescriptors]);

  // Data hooks
  const { data, isLoading, isError, refetch } = useResource(
    type ?? "",
    id ?? "",
    currentVersion ?? 0
  );
  const deleteMutation = useDeleteResource(type ?? "");
  const duplicateMutation = useDuplicateResource(type ?? "");
  const cascadeSave = useCascadeSave(type ?? "");
  const { data: jsonSchema } = useJsonSchema(type);

  // Save feedback state
  const [saveSuccess, setSaveSuccess] = useState(false);

  // Delete dialog state
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  // Usage dialog state (Path B: from resource view, after save)
  const [usages, setUsages] = useState<ResourceUsage[]>([]);
  const [showUsageDialog, setShowUsageDialog] = useState(false);
  const [isCascading, setIsCascading] = useState(false);
  const [newResourceVersion, setNewResourceVersion] = useState<number | null>(null);
  const [previousResourceVersion, setPreviousResourceVersion] = useState<number | null>(null);

  // Agent context for Save & Test
  const agentCtx = useAgentContext();
  const { saveAndDeploy, isRunning: isSaveAndDeploying } = useSaveAndDeploy();

  // Version diff dialog state
  const [showDiff, setShowDiff] = useState(false);

  // Build version list from descriptors
  const versions = versionDescriptors
    ? versionDescriptors.map((d) => {
        const match = d.resource?.match(/\?version=(\d+)/);
        return {
          version: match ? parseInt(match[1] ?? "1", 10) : 1,
          lastModifiedOn: d.lastModifiedOn,
        };
      })
    : [{ version: currentVersion ?? 1 }];

  /**
   * After a cascade that failed partway, move this page onto the versions that
   * now exist. The resource (and perhaps the workflow) already carries a new
   * version, so a retry that still addressed the old one 409'd on every attempt
   * until a reload — which discarded the edit being saved.
   */
  const adoptPartialCascade = useCallback((err: unknown) => {
    const partial = cascadePartialResult(err);
    if (!partial) return;
    if (partial.newResourceVersion !== undefined) {
      setCurrentVersion(partial.newResourceVersion);
    }
    if (partial.retryContext) {
      setCascadeContext(partial.retryContext);
    }
  }, []);

  // All hooks are above — safe to do early returns below

  const handleSave = useCallback(
    async (jsonString: string) => {
      if (currentVersion === undefined) return;
      setSaveSuccess(false);
      try {
        const parsed = JSON.parse(jsonString);

        if (cascadeContext) {
          // Path A: Auto-cascade (navigated from agent/workflow)
          cascadeSave.mutate(
            {
              id: id ?? "",
              version: currentVersion,
              body: parsed,
              context: cascadeContext,
            },
            {
              onSuccess: (result) => {
                const newAgentVersion = result.newAgentVersion;
                /*
                 * "Saved successfully" on its own is misleading here. This path
                 * cascades resource -> workflow -> agent and stops: the running
                 * agent keeps serving the version it was deployed with. Measured
                 * on an eligibility gate with the ceiling lowered from 150,000 to
                 * 50,000 and a case of 85,000 -- after a plain Save the gate still
                 * passed, while the resource/workflow/agent versions had advanced
                 * to v4/v5 with the deployment stuck at v3. Someone who reads
                 * "Saved successfully" at face value has a config that is saved
                 * and not live.
                 *
                 * The toast says so, and offers the one action that closes the
                 * gap, so the fix costs a click rather than a support question.
                 *
                 * The action is offered ONLY when the cascade actually produced a
                 * new agent version. Falling back to the version the URL carried
                 * would deploy a revision that does not contain this edit, while
                 * the toast beside it promises the change will take effect -- a
                 * worse failure than the silence this replaced, because it looks
                 * like it worked.
                 */
                toast.success(t("editor.savedNotLive", "Saved — not yet live"), {
                  /*
                   * A STABLE id, so a second save replaces the first toast rather
                   * than stacking beside it. Each toast's action closes over the
                   * agent version its own save produced, so two live toasts meant
                   * clicking the older one deployed the older configuration --
                   * overwriting the newer one in production, from a control that
                   * looked like it was about the save just made.
                   */
                  id: SAVE_NOT_LIVE_TOAST_ID,
                  description: t(
                    "editor.savedNotLiveDescription",
                    "The running agent still serves the deployed version. Deploy to make this change take effect.",
                  ),
                  action: newAgentVersion
                    ? {
                        label: t("editor.deployNow", "Deploy"),
                        onClick: () => {
                          deployAgent("production", cascadeContext.agentId, newAgentVersion)
                            .then(() => {
                              // Same caches the Save & Deploy flow refreshes: the
                              // agent list and the chat's deployed-agent picker
                              // both render a deployment state that has just
                              // changed underneath them.
                              queryClient.invalidateQueries({ queryKey: ["agents"] });
                              queryClient.invalidateQueries({ queryKey: ["chat", "deployedAgents"] });
                              toast.success(t("editor.deployStarted", "Deployment started"));
                            })
                            .catch((err) => toast.error(getErrorMessage(err)));
                        },
                      }
                    : undefined,
                });
                setSaveSuccess(true);
                setCurrentVersion(result.newResourceVersion);
                // Update cascade context so next save uses new versions
                setCascadeContext(nextCascadeContext(cascadeContext, result));
              },
              onError: (err) => {
                adoptPartialCascade(err);
                toast.error(describeSaveError(err, t));
              },
            }
          );
        } else {
          // Path B: Save config only, then offer usage dialog
          cascadeSave.mutate(
            {
              id: id ?? "",
              version: currentVersion,
              body: parsed,
            },
            {
              onSuccess: async (result) => {
                toast.success(t("editor.saved"));
                setSaveSuccess(true);
                setPreviousResourceVersion(currentVersion);
                setNewResourceVersion(result.newResourceVersion);
                setCurrentVersion(result.newResourceVersion);

                // Check if any agents/workflows reference this
                if (rt) {
                  try {
                    const found = await findResourceUsage(
                      id ?? "",
                      rt.store,
                      rt.plural
                    );
                    if (found.length > 0) {
                      setUsages(found);
                      setShowUsageDialog(true);
                    }
                  } catch {
                    // Silently skip usage lookup failures
                  }
                }
              },
              onError: (err) => toast.error(getErrorMessage(err)),
            }
          );
        }
      } catch {
        // Invalid JSON — shouldn't happen, ConfigEditorLayout validates
      }
    },
    [id, currentVersion, cascadeSave, cascadeContext, rt, t, queryClient, adoptPartialCascade]
  );

  const handleSaveAndDeploy = useCallback(
    async (jsonString: string) => {
      if (!cascadeContext || !agentCtx || currentVersion === undefined) return;
      try {
        const parsed = JSON.parse(jsonString);
        await saveAndDeploy({
          agentId: agentCtx.agentId,
          save: async () => {
            let result;
            try {
              result = await cascadeSave.mutateAsync({
                id: id ?? "",
                version: currentVersion,
                body: parsed,
                context: cascadeContext,
              });
            } catch (err) {
              adoptPartialCascade(err);
              // Save & Deploy shows the error's message as it stands.
              throw err instanceof CascadeReferenceError ? new Error(describeSaveError(err, t)) : err;
            }
            setCurrentVersion(result.newResourceVersion);
            // Update cascade context so next Save & Test uses correct versions
            setCascadeContext(prev => prev ? nextCascadeContext(prev, result) : prev);
            return { newAgentVersion: result.newAgentVersion ?? agentCtx.agentVer };
          },
        });
      } catch {
        // Error handled inside saveAndDeploy
      }
    },
    [id, currentVersion, cascadeSave, cascadeContext, agentCtx, saveAndDeploy, adoptPartialCascade, t]
  );

  const handleCascadeConfirm = useCallback(
    async (selected: ResourceUsage[]) => {
      if (newResourceVersion === null || previousResourceVersion === null || !rt) return;
      setIsCascading(true);

      // Track updated versions across iterations — when the same workflow
      // or agent appears in multiple usages, subsequent cascades must use
      // the version produced by the prior cascade (not the original stale one).
      const updatedWorkflowVersions = new Map<string, number>();
      const updatedAgentVersions = new Map<string, number>();
      let failCount = 0;

      try {
        for (const usage of selected) {
          const workflowVersion =
            updatedWorkflowVersions.get(usage.workflowId) ?? usage.workflowVersion;
          const agentVersion =
            updatedAgentVersions.get(usage.agentId) ?? usage.agentVersion;

          try {
            const result = await cascadeVersionUpdate(
              rt,
              id ?? "",
              previousResourceVersion,
              newResourceVersion,
              {
                workflowId: usage.workflowId,
                workflowVersion,
                agentId: usage.agentId,
                agentVersion,
                /*
                 * A workflow shared by two agents has moved on after the first
                 * one's cascade, but the second agent still references the
                 * version the usage scan found. Say so — the cascade checks the
                 * agent's reference before it writes, and an agent pointing at
                 * a version other than the one it expects is refused.
                 */
                ...(workflowVersion !== usage.workflowVersion
                  ? { agentWorkflowVersion: usage.workflowVersion }
                  : {}),
              },
            );

            if (result.newWorkflowVersion) {
              updatedWorkflowVersions.set(usage.workflowId, result.newWorkflowVersion);
            }
            if (result.newAgentVersion) {
              updatedAgentVersions.set(usage.agentId, result.newAgentVersion);
            }
          } catch (err) {
            failCount++;
            // The workflow may already carry the new reference even though the
            // agent hop failed; a later usage of the same workflow must build on
            // that version, not 409 on the one it replaced.
            const partial = cascadePartialResult(err);
            if (partial?.newWorkflowVersion !== undefined) {
              updatedWorkflowVersions.set(usage.workflowId, partial.newWorkflowVersion);
            }
          }
        }
      } finally {
        if (failCount > 0) {
          toast.error(
            t("editor.cascadePartialFailure", {
              count: failCount,
              defaultValue: "{{count}} cascade update failed",
              defaultValue_other: "{{count}} cascade updates failed",
            })
          );
        } else if (selected.length > 0) {
          toast.success(t("editor.cascadeSuccess", "References updated successfully"));
        }
        setIsCascading(false);
        setShowUsageDialog(false);
        setUsages([]);
      }
    },
    [id, previousResourceVersion, newResourceVersion, rt, t]
  );

  if (!rt) {
    return (
      <div className="space-y-4 py-20">
        <ErrorState message={t("resources.unknownType", "Unknown resource type")} />
        <div className="text-center">
          <Link
            to="/manage/resources"
            className="text-sm text-primary hover:underline"
          >
            {t("resources.backToResources", "← Back to Resources")}
          </Link>
        </div>
      </div>
    );
  }



  function handleDelete() {
    deleteMutation.mutate(
      { id: id ?? "", version: currentVersion ?? 1 },
      {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          setShowDeleteDialog(false);
          navigate(`/manage/resources/${type}`);
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  function handleDuplicate() {
    duplicateMutation.mutate(
      { id: id ?? "", version: currentVersion ?? 1 },
      {
        onSuccess: (result) => {
          toast.success(t("common.duplicate") + " ✓");
          const parts = result.location.split("/");
          const newId = (parts[parts.length - 1] ?? "").split("?")[0];
          if (newId) {
            navigate(`/manage/resources/${type}/${newId}`);
          }
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      }
    );
  }

  return (
    <div className="space-y-6">
      {/* Back link — context-aware: cascade context → back to workflow, otherwise → back to list */}
      {(() => {
        const wfId = searchParams.get("wfId");
        const agId = searchParams.get("agentId");
        const agVer = searchParams.get("agentVer");
        if (wfId) {
          // Navigated from a workflow — go back to the workflow detail
          const params = new URLSearchParams();
          if (agId) params.set("agentId", agId);
          if (agVer) params.set("agentVer", agVer);
          const qs = params.toString();
          return (
            <BackLink
              to={`/manage/workflowview/${wfId}${qs ? `?${qs}` : ""}`}
              label={t("resources.backToWorkflow", "Back to Workflow")}
            />
          );
        }
        return (
          <BackLink
            to={`/manage/resources/${type}`}
            label={t("resources.backToList", {
              type: typeName,
              defaultValue: `Back to ${typeName}`,
            })}
          />
        );
      })()}

      {/* Header with actions */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Icon className="h-8 w-8 text-primary" />
            {(() => {
              const desc = versionDescriptors?.find(d => {
                const match = d.resource?.match(/\?version=(\d+)/);
                return match ? parseInt(match[1]!, 10) === currentVersion : false;
              });
              return desc?.name || typeName;
            })()}
          </h1>
          <p className="mt-1 font-mono text-xs text-muted-foreground">
            {id}
            <span className="ms-2 inline-flex items-center rounded-md bg-primary/10 px-1.5 py-0.5 text-xs font-semibold text-primary">
              v{currentVersion}
            </span>
          </p>
          {cascadeContext && (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
              {t(
                "editor.cascadeMode",
                "Changes will cascade to parent workflow and agent"
              )}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={handleDuplicate}
            disabled={duplicateMutation.isPending}
          >
            <Copy className="h-4 w-4" />
            {t("common.duplicate")}
          </Button>
          <Button
            variant="destructive"
            onClick={() => setShowDeleteDialog(true)}
            disabled={deleteMutation.isPending}
          >
            <Trash2 className="h-4 w-4" />
            {t("common.delete")}
          </Button>
        </div>
      </div>

      {/* Content */}
      {(isLoading || isVersionsLoading || (currentVersion === undefined && !isVersionsError)) && (
        <div className="space-y-4">
          <div className="flex gap-4">
            <Skeleton className="h-10 w-32" />
            <Skeleton className="h-10 w-32" />
          </div>
          <Skeleton className="h-[400px] w-full rounded-xl" />
        </div>
      )}

      {(isError || isVersionsError) && !isLoading && !isVersionsLoading && (
        <ErrorState
          message={t("common.error")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry")}
        />
      )}

      {!isLoading && !isError && data !== undefined && (
        <>
          <ConfigEditorLayout
            typeName={typeName}
            typeIcon={Icon}
            resourceId={id ?? ""}
            data={JSON.stringify(data, null, 2)}
            versions={versions}
            currentVersion={currentVersion ?? 1}
            onVersionChange={setCurrentVersion}
            onSave={handleSave}
            onSaveAndDeploy={cascadeContext && agentCtx ? handleSaveAndDeploy : undefined}
            isSaving={cascadeSave.isPending}
            isSaveAndDeploying={isSaveAndDeploying}
            saveSuccess={saveSuccess}
            saveError={
              cascadeSave.isError
                ? t("editor.saveError", "Failed to save")
                : undefined
            }
            renderFormEditor={EDITOR_MAP[type ?? ""]}
            jsonSchema={jsonSchema}
            onCompare={() => setShowDiff(true)}
          />
          {/* Version diff dialog */}
          {showDiff && (
            <VersionDiffDialog
              open={showDiff}
              onClose={() => setShowDiff(false)}
              typeName={typeName}
              versions={versions}
              currentVersion={currentVersion ?? 1}
              fetchVersion={async (ver: number) => {
                const data = await getResource(rt, id ?? "", ver);
                return JSON.stringify(data, null, 2);
              }}
            />
          )}
          {showUsageDialog && (
            <UpdateUsageDialog
              usages={usages}
              isUpdating={isCascading}
              onConfirm={handleCascadeConfirm}
              onDismiss={() => {
                setShowUsageDialog(false);
                setUsages([]);
              }}
            />
          )}
        </>
      )}

      {/* Delete confirmation */}
      <AlertDialog
        open={showDeleteDialog}
        onOpenChange={setShowDeleteDialog}
        title={t("resources.confirmDelete", { type: typeName })}
        description={t("resources.confirmDeleteDescription", { type: typeName, defaultValue: "This action cannot be undone. The {{type}} will be permanently deleted." })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={handleDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
