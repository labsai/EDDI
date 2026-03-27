import { useState, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { JsonEditor } from "./json-editor";
import { VersionPicker, type VersionInfo } from "./version-picker";
import { Save, Undo2, FileCode, FormInput, AlertCircle, GitCompareArrows, Rocket } from "lucide-react";
import type { LucideIcon } from "lucide-react";

export interface ConfigEditorLayoutProps {
  /** Resource display name (e.g. "Behavior Rules") */
  typeName: string;
  /** Type icon */
  typeIcon?: LucideIcon;
  /** Resource ID (shown in subtitle) */
  resourceId: string;
  /** Stringified JSON of the resource data */
  data: string;
  /** Available versions */
  versions: VersionInfo[];
  /** Currently selected version */
  currentVersion: number;
  /** Called when user selects a different version */
  onVersionChange: (version: number) => void;
  /** Called when user saves */
  onSave: (data: string) => void;
  /** Called when user clicks "Save & Test" (save + deploy + open chat) */
  onSaveAndDeploy?: (data: string) => void;
  /** Whether a save is in progress */
  isSaving?: boolean;
  /** Whether a save-and-deploy is in progress */
  isSaveAndDeploying?: boolean;
  /** Whether the save succeeded (for toast feedback) */
  saveSuccess?: boolean;
  /** Save error message */
  saveError?: string;
  /** Read-only mode */
  readOnly?: boolean;
  /** Static children for the form view (fallback placeholder if absent) */
  children?: React.ReactNode;
  /**
   * Render prop for form editors that need two-way data binding.
   * Receives the current parsed data and an onChange callback.
   * When provided, this takes precedence over static children.
   */
  renderFormEditor?: (
    parsedData: unknown,
    onChange: (updated: unknown) => void,
    readOnly: boolean,
    meta: { resourceId: string; version: number },
  ) => React.ReactNode;
  /** Optional JSON Schema for Monaco validation and autocomplete */
  jsonSchema?: object;
  /** Called when user wants to compare versions (opens diff dialog) */
  onCompare?: () => void;
}

type EditorTab = "form" | "json";

/**
 * Shared editor layout with Form↔JSON tab toggle.
 * This is the chrome around every config editor.
 * Form tab renders children (specific editor component) or a placeholder.
 * JSON tab renders the Monaco editor.
 * both share the same data string.
 */
export function ConfigEditorLayout({
  typeName,
  typeIcon: Icon,
  resourceId,
  data,
  versions,
  currentVersion,
  onVersionChange,
  onSave,
  onSaveAndDeploy,
  isSaving = false,
  isSaveAndDeploying = false,
  saveSuccess = false,
  saveError,
  readOnly = false,
  children,
  renderFormEditor,
  jsonSchema,
  onCompare,
}: ConfigEditorLayoutProps) {
  const { t } = useTranslation();
  const hasFormEditor = !!(renderFormEditor || children);
  const [activeTab, setActiveTab] = useState<EditorTab>(
    hasFormEditor ? "form" : "json"
  );
  const [editedData, setEditedData] = useState(data);

  // Reset edited data when the source data changes (version switch, initial load)
  useMemo(() => {
    setEditedData(data);
  }, [data]);

  // Handler for form editors to push data changes
  const handleFormChange = useCallback((updated: unknown) => {
    setEditedData(JSON.stringify(updated, null, 2));
  }, []);

  const isDirty = editedData !== data;

  // Discard confirmation state
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);

  // Navigation guard — shows native browser prompt on close/reload when dirty
  useUnsavedChangesGuard(isDirty && !readOnly);

  const handleDiscard = useCallback(() => {
    setEditedData(data);
    setShowDiscardConfirm(false);
  }, [data]);

  const handleSave = useCallback(() => {
    // Validate JSON before saving
    try {
      JSON.parse(editedData);
      onSave(editedData);
    } catch {
      // Invalid JSON — don't save (Monaco will show squiggly lines)
    }
  }, [editedData, onSave]);

  const handleSaveAndDeploy = useCallback(() => {
    if (!onSaveAndDeploy) return;
    try {
      JSON.parse(editedData);
      onSaveAndDeploy(editedData);
    } catch {
      // Invalid JSON
    }
  }, [editedData, onSaveAndDeploy]);

  const handleJsonChange = useCallback((val: string) => {
    setEditedData(val);
  }, []);

  return (
    <div className="space-y-4" data-testid="config-editor-layout">
      {/* Header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          {Icon && <Icon className="h-6 w-6 text-primary" />}
          <div>
            <h2 className="text-lg font-semibold text-foreground">{typeName}</h2>
            <p className="font-mono text-xs text-muted-foreground">{resourceId}</p>
          </div>
          <VersionPicker
            versions={versions}
            current={currentVersion}
            onChange={onVersionChange}
            disabled={isDirty || isSaving || isSaveAndDeploying}
          />
          {onCompare && versions.length > 1 && (
            <button
              onClick={onCompare}
              className="inline-flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
              title={t("editor.compareVersions", "Compare Versions")}
              data-testid="compare-versions-btn"
            >
              <GitCompareArrows className="h-3.5 w-3.5" />
              {t("editor.compare", "Compare")}
            </button>
          )}
          {isDirty && (
            <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-400" data-testid="dirty-indicator">
              <AlertCircle className="h-3 w-3" />
              {t("editor.dirty", "Unsaved changes")}
            </span>
          )}
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2">
          {saveSuccess && (
            <span className="text-xs font-medium text-emerald-600 dark:text-emerald-400" data-testid="save-success">
              ✓ {t("editor.saved", "Saved successfully")}
            </span>
          )}
          {saveError && (
            <span className="text-xs font-medium text-destructive" data-testid="save-error">
              {saveError}
            </span>
          )}
          {!readOnly && (
            <>
              <button
                onClick={() => setShowDiscardConfirm(true)}
                disabled={!isDirty || isSaving || isSaveAndDeploying}
                className="inline-flex items-center gap-1.5 rounded-lg border border-input px-3 py-2 text-sm font-medium text-foreground shadow-sm transition-all hover:bg-secondary active:scale-[0.98] disabled:opacity-50"
                data-testid="discard-btn"
              >
                <Undo2 className="h-4 w-4" />
                {t("editor.discard", "Discard")}
              </button>
              <button
                onClick={handleSave}
                disabled={!isDirty || isSaving || isSaveAndDeploying}
                className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-all hover:bg-primary/90 active:scale-[0.98] disabled:opacity-50"
                data-testid="save-btn"
              >
                <Save className="h-4 w-4" />
                {isSaving
                  ? t("editor.saving", "Saving...")
                  : t("editor.save", "Save")}
              </button>
              {onSaveAndDeploy && (
                <button
                  onClick={handleSaveAndDeploy}
                  disabled={!isDirty || isSaving || isSaveAndDeploying}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3 py-2 text-sm font-medium text-white shadow-sm transition-all hover:bg-emerald-700 active:scale-[0.98] disabled:opacity-50 dark:bg-emerald-600 dark:hover:bg-emerald-700"
                  data-testid="save-test-btn"
                >
                  <Rocket className="h-4 w-4" />
                  {isSaveAndDeploying
                    ? t("editor.deploying", "Deploying…")
                    : t("editor.saveAndTest", "Save & Test")}
                </button>
              )}
            </>
          )}
        </div>
      </div>

      {/* Tab bar */}
      <div
        className="flex gap-1 rounded-lg bg-muted p-1"
        data-testid="editor-tabs"
        role="tablist"
        aria-label={typeName}
        onKeyDown={(e) => {
          if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
            e.preventDefault();
            const next = e.key === "ArrowRight"
              ? (activeTab === "form" ? "json" : "form")
              : (activeTab === "json" ? "form" : "json");
            setActiveTab(next as EditorTab);
            // Focus the newly active tab
            requestAnimationFrame(() => {
              const btn = document.getElementById(`editor-tab-${next}`);
              btn?.focus();
            });
          }
        }}
      >
        <button
          id="editor-tab-form"
          role="tab"
          aria-selected={activeTab === "form"}
          aria-controls="editor-tabpanel-form"
          tabIndex={activeTab === "form" ? 0 : -1}
          onClick={() => setActiveTab("form")}
          className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition-all ${
            activeTab === "form"
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground"
          }`}
          data-testid="tab-form"
        >
          <FormInput className="h-4 w-4" aria-hidden="true" />
          {t("editor.formTab", "Form")}
        </button>
        <button
          id="editor-tab-json"
          role="tab"
          aria-selected={activeTab === "json"}
          aria-controls="editor-tabpanel-json"
          tabIndex={activeTab === "json" ? 0 : -1}
          onClick={() => setActiveTab("json")}
          className={`inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition-all ${
            activeTab === "json"
              ? "bg-background text-foreground shadow-sm"
              : "text-muted-foreground hover:text-foreground"
          }`}
          data-testid="tab-json"
        >
          <FileCode className="h-4 w-4" aria-hidden="true" />
          {t("editor.jsonTab", "JSON")}
        </button>
      </div>

      {/* Tab content */}
      <div className="rounded-xl border bg-card shadow-sm">
        {activeTab === "form" ? (
          <div
            id="editor-tabpanel-form"
            role="tabpanel"
            aria-labelledby="editor-tab-form"
            className="p-6"
            data-testid="form-view"
          >
            {renderFormEditor ? (
              (() => {
                try {
                  const parsed = JSON.parse(editedData);
                  return renderFormEditor(parsed, handleFormChange, readOnly, { resourceId, version: currentVersion });
                } catch {
                  return (
                    <div className="text-sm text-destructive" role="alert">
                      {t(
                        "editor.invalidJson",
                        "Invalid JSON — switch to the JSON tab to fix."
                      )}
                    </div>
                  );
                }
              })()
            ) : children ? (
              children
            ) : (
              <div className="flex flex-col items-center justify-center py-16 text-center">
                <FormInput className="h-10 w-10 text-muted-foreground/50" aria-hidden="true" />
                <p className="mt-3 text-sm text-muted-foreground">
                  {t(
                    "editor.formPlaceholder",
                    "Visual editor coming soon. Use the JSON tab to edit."
                  )}
                </p>
              </div>
            )}
          </div>
        ) : (
          <div
            id="editor-tabpanel-json"
            role="tabpanel"
            aria-labelledby="editor-tab-json"
            className="p-2"
            data-testid="json-view"
          >
            <JsonEditor
              value={editedData}
              onChange={readOnly ? undefined : handleJsonChange}
              readOnly={readOnly}
              height="500px"
              jsonSchema={jsonSchema}
            />
          </div>
        )}
      </div>

      {/* Discard confirmation dialog */}
      <UnsavedChangesDialog
        open={showDiscardConfirm}
        onConfirm={handleDiscard}
        onCancel={() => setShowDiscardConfirm(false)}
        title={t("editor.discardTitle", "Discard Changes?")}
        message={t("editor.discardMessage", "Are you sure you want to discard all unsaved changes? This action cannot be undone.")}
      />

    </div>
  );
}
