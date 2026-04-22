import { useState, useCallback, useEffect } from "react";
import { useParams, Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
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
import { VersionDiffDialog } from "@/components/editors/version-diff-dialog";
import { getResource } from "@/lib/api/resources";
import { useAgentContext } from "@/hooks/use-agent-context";
import { useSaveAndDeploy } from "@/hooks/use-save-and-deploy";

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


export function ResourceDetailPage() {
  const { type, id } = useParams<{ type: string; id: string }>();
  const [searchParams] = useSearchParams();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const rt = getResourceType(type ?? "");
  const Icon = ICON_MAP[rt?.icon ?? ""] ?? FileCode;
  const typeName = rt ? t(`${rt.labelKey}.name`) : type ?? "";

  // Cascade context from URL search params (set when navigating from agent/package)
  const cascadeContext: CascadeContext | undefined = (() => {
    const pkgId = searchParams.get("pkgId");
    const pkgVer = searchParams.get("pkgVer");
    const agentId = searchParams.get("agentId");
    const agentVer = searchParams.get("agentVer");
    if (pkgId && pkgVer && agentId && agentVer) {
      return {
        workflowId: pkgId,
        packageVersion: parseInt(pkgVer, 10),
        agentId: agentId,
        agentVersion: parseInt(agentVer, 10),
      };
    }
    return undefined;
  })();

  // Version state — default to latest version once descriptors are loaded
  const [currentVersion, setCurrentVersion] = useState<number | undefined>(undefined);

  // Fetch version descriptors first — needed to resolve the latest version
  const { data: versionDescriptors } = useResourceVersions(type ?? "", id ?? "");

  // Resolve latest version from descriptors
  useEffect(() => {
    if (currentVersion === undefined && versionDescriptors && versionDescriptors.length > 0) {
      const latest = versionDescriptors.reduce((max, d) => {
        const match = d.resource?.match(/\?version=(\d+)/);
        const v = match ? parseInt(match[1] ?? "1", 10) : 1;
        return v > max ? v : max;
      }, 1);
      setCurrentVersion(latest);
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

  // All hooks are above — safe to do early returns below

  const handleSave = useCallback(
    async (jsonString: string) => {
      if (currentVersion === undefined) return;
      setSaveSuccess(false);
      try {
        const parsed = JSON.parse(jsonString);

        if (cascadeContext) {
          // Path A: Auto-cascade (navigated from agent/package)
          cascadeSave.mutate(
            {
              id: id ?? "",
              version: currentVersion,
              body: parsed,
              context: cascadeContext,
            },
            {
              onSuccess: (result) => {
                toast.success(t("editor.saved"));
                setSaveSuccess(true);
                setCurrentVersion(result.newResourceVersion);
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
                setNewResourceVersion(result.newResourceVersion);
                setCurrentVersion(result.newResourceVersion);

                // Check if any agents/packages reference this
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
            }
          );
        }
      } catch {
        // Invalid JSON — shouldn't happen, ConfigEditorLayout validates
      }
    },
    [id, currentVersion, cascadeSave, cascadeContext, rt, t]
  );

  const handleSaveAndDeploy = useCallback(
    async (jsonString: string) => {
      if (!cascadeContext || !agentCtx || currentVersion === undefined) return;
      try {
        const parsed = JSON.parse(jsonString);
        await saveAndDeploy({
          agentId: agentCtx.agentId,
          save: async () => {
            const result = await cascadeSave.mutateAsync({
              id: id ?? "",
              version: currentVersion,
              body: parsed,
              context: cascadeContext,
            });
            setCurrentVersion(result.newResourceVersion);
            return { newAgentVersion: result.newAgentVersion ?? agentCtx.agentVer };
          },
        });
      } catch {
        // Error handled inside saveAndDeploy
      }
    },
    [id, currentVersion, cascadeSave, cascadeContext, agentCtx, saveAndDeploy]
  );

  const handleCascadeConfirm = useCallback(
    async (selected: ResourceUsage[]) => {
      if (newResourceVersion === null || !rt) return;
      setIsCascading(true);

      try {
        for (const usage of selected) {
          await cascadeSave.mutateAsync({
            id: id ?? "",
            version: newResourceVersion,
            body: {}, // Not used — skipResourceSave bypasses the resource PUT
            skipResourceSave: true,
            context: {
              workflowId: usage.workflowId,
              packageVersion: usage.packageVersion,
              agentId: usage.agentId,
              agentVersion: usage.agentVersion,
            },
          });
        }
      } catch {
        // Some cascades may fail — that's okay
      } finally {
        setIsCascading(false);
        setShowUsageDialog(false);
        setUsages([]);
      }
    },
    [id, newResourceVersion, rt, cascadeSave]
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
        const pkgId = searchParams.get("pkgId");
        const agId = searchParams.get("agentId");
        const agVer = searchParams.get("agentVer");
        if (pkgId) {
          // Navigated from a workflow — go back to the workflow detail
          const params = new URLSearchParams();
          if (agId) params.set("agentId", agId);
          if (agVer) params.set("agentVer", agVer);
          const qs = params.toString();
          return (
            <BackLink
              to={`/manage/workflowview/${pkgId}${qs ? `?${qs}` : ""}`}
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
      {isLoading && (
        <div className="space-y-4">
          <div className="flex gap-4">
            <Skeleton className="h-10 w-32" />
            <Skeleton className="h-10 w-32" />
          </div>
          <Skeleton className="h-[400px] w-full rounded-xl" />
        </div>
      )}

      {isError && (
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
              currentVersion={currentVersion}
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
