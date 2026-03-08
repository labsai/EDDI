import { useState, useCallback } from "react";
import { useParams, Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  ArrowLeft,
  RefreshCw,
  AlertCircle,
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

export function ResourceDetailPage() {
  const { type, id } = useParams<{ type: string; id: string }>();
  const [searchParams] = useSearchParams();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const rt = getResourceType(type ?? "");
  const Icon = ICON_MAP[rt?.icon ?? ""] ?? FileCode;
  const typeName = rt ? t(`${rt.labelKey}.name`) : type ?? "";

  // Cascade context from URL search params (set when navigating from bot/package)
  const cascadeContext: CascadeContext | undefined = (() => {
    const pkgId = searchParams.get("pkgId");
    const pkgVer = searchParams.get("pkgVer");
    const botId = searchParams.get("botId");
    const botVer = searchParams.get("botVer");
    if (pkgId && pkgVer && botId && botVer) {
      return {
        packageId: pkgId,
        packageVersion: parseInt(pkgVer, 10),
        botId: botId,
        botVersion: parseInt(botVer, 10),
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

  // Save feedback state
  const [saveSuccess, setSaveSuccess] = useState(false);

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
          // Path A: Auto-cascade (navigated from bot/package)
          cascadeSave.mutate(
            {
              id: id ?? "",
              version: currentVersion,
              body: parsed,
              context: cascadeContext,
            },
            {
              onSuccess: (result) => {
                setSaveSuccess(true);
                setCurrentVersion(result.newResourceVersion);
                setTimeout(() => setSaveSuccess(false), 3000);
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
                setSaveSuccess(true);
                setNewResourceVersion(result.newResourceVersion);
                setCurrentVersion(result.newResourceVersion);
                setTimeout(() => setSaveSuccess(false), 3000);

                // Check if any bots/packages reference this
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
              packageId: usage.packageId,
              packageVersion: usage.packageVersion,
              botId: usage.botId,
              botVersion: usage.botVersion,
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
      <div className="flex flex-col items-center justify-center py-20">
        <AlertCircle className="h-12 w-12 text-destructive" />
        <p className="mt-4 text-lg font-medium text-destructive">
          {t("resources.unknownType", "Unknown resource type")}
        </p>
        <Link
          to="/manage/resources"
          className="mt-4 text-sm text-primary hover:underline"
        >
          {t("resources.backToResources", "← Back to Resources")}
        </Link>
      </div>
    );
  }

  function handleDelete() {
    if (
      window.confirm(
        t("resources.confirmDelete", {
          type: typeName,
          defaultValue: `Are you sure you want to delete this ${typeName}?`,
        })
      )
    ) {
      deleteMutation.mutate(
        { id: id ?? "", version: currentVersion },
        { onSuccess: () => navigate(`/manage/resources/${type}`) }
      );
    }
  }

  function handleDuplicate() {
    duplicateMutation.mutate(
      { id: id ?? "", version: currentVersion },
      {
        onSuccess: (result) => {
          const parts = result.location.split("/");
          const newId = (parts[parts.length - 1] ?? "").split("?")[0];
          if (newId) {
            navigate(`/manage/resources/${type}/${newId}`);
          }
        },
      }
    );
  }

  return (
    <div className="space-y-6">
      {/* Back link */}
      <Link
        to={`/manage/resources/${type}`}
        className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
        data-testid="back-to-list"
      >
        <ArrowLeft className="h-4 w-4" />
        {t("resources.backToList", {
          type: typeName,
          defaultValue: `Back to ${typeName}`,
        })}
      </Link>

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
                "Changes will cascade to parent package and bot"
              )}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleDuplicate}
            disabled={duplicateMutation.isPending}
            className="inline-flex items-center gap-2 rounded-lg border border-input px-4 py-2.5 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary active:scale-[0.98] disabled:opacity-50"
          >
            <Copy className="h-4 w-4" />
            {t("common.duplicate")}
          </button>
          <button
            onClick={handleDelete}
            disabled={deleteMutation.isPending}
            className="inline-flex items-center gap-2 rounded-lg bg-destructive/10 px-4 py-2.5 text-sm font-medium text-destructive shadow-sm transition-all hover:bg-destructive/20 active:scale-[0.98] disabled:opacity-50"
          >
            <Trash2 className="h-4 w-4" />
            {t("common.delete")}
          </button>
        </div>
      </div>

      {/* Content */}
      {isLoading && (
        <div className="flex items-center justify-center py-20">
          <RefreshCw className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {isError && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-16">
          <AlertCircle className="h-12 w-12 text-destructive" />
          <p className="mt-4 text-lg font-medium text-destructive">
            {t("common.error")}
          </p>
          <button
            onClick={() => refetch()}
            className="mt-4 rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors"
          >
            {t("common.retry")}
          </button>
        </div>
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
            renderFormEditor={
              type === "behavior"
                ? (parsed, onFormChange, ro) => (
                    <BehaviorEditor
                      data={parsed as BehaviorConfig}
                      onChange={onFormChange}
                      readOnly={ro}
                    />
                  )
                : type === "httpcalls"
                  ? (parsed, onFormChange, ro) => (
                      <HttpCallsEditor
                        data={parsed as HttpCallsConfig}
                        onChange={onFormChange}
                        readOnly={ro}
                      />
                    )
                  : type === "langchain"
                    ? (parsed, onFormChange, ro) => (
                        <LangchainEditor
                          data={parsed as LangchainConfig}
                          onChange={onFormChange}
                          readOnly={ro}
                        />
                      )
                    : type === "output"
                      ? (parsed, onFormChange, ro) => (
                          <OutputEditor
                            data={parsed as OutputConfig}
                            onChange={onFormChange}
                            readOnly={ro}
                          />
                        )
                      : type === "propertysetter"
                        ? (parsed, onFormChange, ro) => (
                            <PropertySetterEditor
                              data={parsed as PropertySetterConfig}
                              onChange={onFormChange}
                              readOnly={ro}
                            />
                          )
                        : type === "dictionaries"
                          ? (parsed, onFormChange, ro) => (
                              <DictionaryEditor
                                data={parsed as DictionaryConfig}
                                onChange={onFormChange}
                                readOnly={ro}
                              />
                            )
                          : undefined
            }
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
    </div>
  );
}
