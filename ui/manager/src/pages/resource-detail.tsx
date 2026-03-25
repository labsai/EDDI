import { useState, useCallback, type ReactNode } from "react";
import { useParams, Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  FileCode,
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
  Trash2,
  Copy,
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
import { UpdateUsageDialog } from "@/components/editors/update-usage-dialog";
import {
  findResourceUsage,
  type ResourceUsage,
} from "@/lib/api/resource-usage";
import { useJsonSchema } from "@/hooks/use-json-schema";
import type { CascadeContext } from "@/lib/api/cascade-save";
import {
  BehaviorEditor,
  type BehaviorConfig,
} from "@/components/editors/behavior-editor";
import {
  HttpCallsEditor,
  type HttpCallsConfig,
} from "@/components/editors/httpcalls-editor";
import {
  LangchainEditor,
  type LangchainConfig,
} from "@/components/editors/langchain-editor";
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

const ICON_MAP: Record<string, LucideIcon> = {
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  Brain,
  Settings,
};

// Clean editor lookup — replaces the 6-level nested ternary
const EDITOR_MAP: Record<
  string,
  (parsed: unknown, onChange: (val: unknown) => void, readOnly: boolean) => ReactNode
> = {
  rules: (p, o, r) => (
    <BehaviorEditor data={p as BehaviorConfig} onChange={o} readOnly={r} />
  ),
  apicalls: (p, o, r) => (
    <HttpCallsEditor data={p as HttpCallsConfig} onChange={o} readOnly={r} />
  ),
  llm: (p, o, r) => (
    <LangchainEditor data={p as LangchainConfig} onChange={o} readOnly={r} />
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

  // Version state
  const [currentVersion, setCurrentVersion] = useState(1);

  // Data hooks
  const { data, isLoading, isError, refetch } = useResource(
    type ?? "",
    id ?? "",
    currentVersion
  );
  const { data: versionDescriptors } = useResourceVersions(type ?? "", id ?? "");
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

  // Build version list from descriptors
  const versions = versionDescriptors
    ? versionDescriptors.map((d) => {
        const match = d.resource?.match(/\?version=(\d+)/);
        return {
          version: match ? parseInt(match[1] ?? "1", 10) : 1,
          lastModifiedOn: d.lastModifiedOn,
        };
      })
    : [{ version: currentVersion }];

  // All hooks are above — safe to do early returns below

  const handleSave = useCallback(
    async (jsonString: string) => {
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
    [id, currentVersion, cascadeSave, cascadeContext, rt]
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
            body: {}, // Body not needed — config already saved in step 1
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
      { id: id ?? "", version: currentVersion },
      {
        onSuccess: () => {
          toast.success(t("common.delete") + " ✓");
          navigate(`/manage/resources/${type}`);
        },
        onError: () => toast.error(t("common.error")),
      }
    );
    setShowDeleteDialog(false);
  }

  function handleDuplicate() {
    duplicateMutation.mutate(
      { id: id ?? "", version: currentVersion },
      {
        onSuccess: (result) => {
          toast.success(t("common.duplicate") + " ✓");
          const parts = result.location.split("/");
          const newId = (parts[parts.length - 1] ?? "").split("?")[0];
          if (newId) {
            navigate(`/manage/resources/${type}/${newId}`);
          }
        },
        onError: () => toast.error(t("common.error")),
      }
    );
  }

  return (
    <div className="space-y-6">
      {/* Back link */}
      <BackLink
        to={`/manage/resources/${type}`}
        label={t("resources.backToList", {
          type: typeName,
          defaultValue: `Back to ${typeName}`,
        })}
      />

      {/* Header with actions */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Icon className="h-8 w-8 text-primary" />
            {typeName}
          </h1>
          <p className="mt-1 font-mono text-xs text-muted-foreground">{id}</p>
          {cascadeContext && (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
              {t(
                "editor.cascadeMode",
                "Changes will cascade to parent package and agent"
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
            renderFormEditor={EDITOR_MAP[type ?? ""]}
            jsonSchema={jsonSchema}
          />
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
        description={t("resources.confirmDelete", { type: typeName })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={handleDelete}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
