import { useState, useEffect, useCallback, useMemo, useRef } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { UnsavedChangesDialog } from "@eddi/app-kit/components/ui/unsaved-changes-dialog";
import { useUnsavedChangesGuard } from "@eddi/app-kit/hooks/use-unsaved-changes-guard";
import { EditorValidityContext, type EditorValidity } from "@eddi/app-kit/components/editors/editor-validity";
import { JsonEditor } from "./json-editor";
import type { DocumentValidator } from "./document-validators";
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
  /**
   * Warnings the last successful save answered with ("saved, but …"). Shown
   * until the next edit or save.
   */
  saveWarnings?: string[];
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
    meta: { resourceId: string; version: number; isDirty: boolean },
  ) => React.ReactNode;
  /** Optional JSON Schema for Monaco validation and autocomplete */
  jsonSchema?: object;
  /** Called when user wants to compare versions (opens diff dialog) */
  onCompare?: () => void;
  /**
   * Reports the working copy whenever it changes. For a host that must act on
   * unsaved edits from outside the layout: the Studio asks before it replaces
   * this editor with another stage's, and saves from there if asked to.
   */
  onDraftChange?: (draft: { data: string; dirty: boolean }) => void;
  /**
   * Blocking checks on the parsed document, run whichever tab is showing — the
   * form editor's own reports vanish with the form, so without this a value the
   * form refuses could be saved from the JSON tab.
   */
  validateDocument?: DocumentValidator;
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
  saveWarnings,
  readOnly = false,
  children,
  renderFormEditor,
  jsonSchema,
  onCompare,
  onDraftChange,
  validateDocument,
}: ConfigEditorLayoutProps) {
  const { t } = useTranslation();
  const hasFormEditor = !!(renderFormEditor || children);
  const [activeTab, setActiveTab] = useState<EditorTab>(
    hasFormEditor ? "form" : "json"
  );
  const [editedData, setEditedData] = useState(data);
  /** The text last handed to `onSave` / `onSaveAndDeploy`, while that save is in flight. */
  const savedPayloadRef = useRef<string | null>(null);
  /** The source text `editedData` was last reset to. */
  const syncedDataRef = useRef(data);
  /** `editedData` as of the last render, for the reset effect below. */
  const editedDataRef = useRef(editedData);
  editedDataRef.current = editedData;
  /** Why the last Save click did nothing — shown instead of a silent no-op. */
  const [invalidJson, setInvalidJson] = useState<string | null>(null);
  /** Field problems the form editor reported (id -> reason); any entry blocks Save. */
  const [fieldProblems, setFieldProblems] = useState<Record<string, string>>({});
  const validity = useMemo<EditorValidity>(
    () => ({
      report: (id, reason) =>
        setFieldProblems((prev) => {
          if (reason === null) {
            if (!(id in prev)) return prev;
            const next = { ...prev };
            delete next[id];
            return next;
          }
          return prev[id] === reason ? prev : { ...prev, [id]: reason };
        }),
    }),
    [],
  );
  // The document-level check sees the JSON as it will be saved, on either tab.
  const documentProblem = useMemo(() => {
    if (!validateDocument) return null;
    try {
      return validateDocument(JSON.parse(editedData), t);
    } catch {
      return null; // unparseable JSON has its own refusal
    }
  }, [validateDocument, editedData, t]);
  const blockingReason = Object.values(fieldProblems)[0] ?? documentProblem;
  /** The warnings array the user dismissed — a newer save brings a new array. */
  const [dismissedWarnings, setDismissedWarnings] = useState<string[] | undefined>(undefined);

  // A failed save must not cost the draft: the buffer is what the user typed, and
  // `data` did not change. The ref only exists to recognise "this is what was
  // saved" when the refetch lands — after a failure there is no such refetch, and
  // leaving it set let an unrelated later refetch (a window focus, another tab's
  // save) overwrite the draft because it still equalled the "saved" payload.
  useEffect(() => {
    if (saveError) savedPayloadRef.current = null;
  }, [saveError]);

  // Reset edited data when the source data changes (version switch, initial
  // load, the new version a save created) — but only when doing so loses
  // nothing. A save refetches and changes `data`; an unconditional reset threw
  // away whatever was typed while the save was in flight. The edit buffer is
  // replaced only if it is untouched since the last reset, or still exactly
  // what was saved.
  //
  // Decided here, not inside a `setEditedData(current => …)` updater: the
  // decision moves the refs it reads, and React may call an updater more than
  // once (StrictMode, a re-run render) — a second call would see the refs the
  // first one moved and keep the stale document.
  useEffect(() => {
    const current = editedDataRef.current;
    const untouched = current === syncedDataRef.current;
    const justSaved = savedPayloadRef.current !== null && current === savedPayloadRef.current;
    syncedDataRef.current = data;
    savedPayloadRef.current = null;
    if (untouched || justSaved) setEditedData(data);
  }, [data]);

  // Handler for form editors to push data changes
  const handleFormChange = useCallback((updated: unknown) => {
    setEditedData(JSON.stringify(updated, null, 2));
  }, []);

  const isDirty = editedData !== data;

  useEffect(() => {
    onDraftChange?.({ data: editedData, dirty: editedData !== data });
  }, [editedData, data, onDraftChange]);

  // Discard confirmation state
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);

  // Navigation guard — the browser prompt on close/reload, and the in-app
  // prompt (rendered below) for links, Back and navigate() calls.
  useUnsavedChangesGuard(isDirty && !readOnly);

  const handleDiscard = useCallback(() => {
    setEditedData(data);
    setShowDiscardConfirm(false);
  }, [data]);

  /**
   * Parse before saving. An invalid document used to make Save a silent no-op —
   * the button stayed enabled, nothing was sent and nothing said why; the only
   * hint was a squiggle in a tab the user might not be looking at.
   */
  const validJsonOrExplain = useCallback((): boolean => {
    try {
      JSON.parse(editedData);
      setInvalidJson(null);
      return true;
    } catch (e) {
      const detail = e instanceof Error ? e.message : String(e);
      setInvalidJson(detail);
      setActiveTab("json");
      // Also a toast: the refusal is easy to miss when the user saved from the
      // form tab, which is not where the inline message renders.
      toast.error(
        t("editor.invalidJsonSave", "Not saved — the JSON is invalid. Fix it in the JSON tab."),
        { description: detail },
      );
      return false;
    }
  }, [editedData, t]);

  const handleSave = useCallback(() => {
    if (blockingReason) return;
    if (!validJsonOrExplain()) return;
    savedPayloadRef.current = editedData;
    onSave(editedData);
  }, [editedData, onSave, validJsonOrExplain, blockingReason]);

  const handleSaveAndDeploy = useCallback(() => {
    if (!onSaveAndDeploy || blockingReason) return;
    if (!validJsonOrExplain()) return;
    savedPayloadRef.current = editedData;
    onSaveAndDeploy(editedData);
  }, [editedData, onSaveAndDeploy, validJsonOrExplain, blockingReason]);

  const handleJsonChange = useCallback((val: string) => {
    setEditedData(val);
    setInvalidJson(null);
  }, []);

  // Ctrl/Cmd+S saves, as in every editor. Re-bound on each change so it never
  // saves stale data.
  const containerRef = useRef<HTMLDivElement>(null);
  const busy = isSaving || isSaveAndDeploying;
  // A failed attempt must not leave the "what was saved" marker behind, even when
  // the parent keeps showing the very same error text across retries: clear it on
  // the way from in-flight to failed.
  const wasBusyRef = useRef(false);
  useEffect(() => {
    if (wasBusyRef.current && !busy && saveError) savedPayloadRef.current = null;
    wasBusyRef.current = busy;
  }, [busy, saveError]);
  useEffect(() => {
    if (readOnly) return;
    function onKeyDown(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "s") {
        // Only for the editor that has focus: several can be mounted at once
        // (the Studio mounts a desktop and a mobile panel), and an unfocused
        // one must not save on a shortcut meant for another.
        const target = e.target instanceof Node ? e.target : document.activeElement;
        if (!containerRef.current || !target || !containerRef.current.contains(target)) return;
        e.preventDefault();
        if (isDirty && !busy && !blockingReason) handleSave();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [readOnly, isDirty, busy, blockingReason, handleSave]);

  return (
    <EditorValidityContext.Provider value={validity}>
    <div ref={containerRef} className="space-y-4" data-testid="config-editor-layout">
      {/* Header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          {Icon && <Icon className="h-6 w-6 text-primary" />}
          <div>
            <h2 className="text-lg font-semibold text-foreground">{typeName}</h2>
            <p className="font-mono text-xs text-muted-foreground">{resourceId}</p>
          </div>
          <span
            title={
              isDirty
                ? t("editor.versionLockedDirty", "Save or discard your changes to switch versions")
                : undefined
            }
          >
            <VersionPicker
              versions={versions}
              current={currentVersion}
              onChange={onVersionChange}
              disabled={isDirty || isSaving || isSaveAndDeploying}
            />
          </span>
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
            <span className="inline-flex items-center gap-1 rounded-full bg-warn-100 px-2 py-0.5 text-xs font-medium text-warn-800 dark:bg-warn-900/30 dark:text-warn-400" data-testid="dirty-indicator">
              <AlertCircle className="h-3 w-3" />
              {t("editor.dirty", "Unsaved changes")}
            </span>
          )}
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2">
          {saveSuccess && !isDirty && (
            <span className="text-xs font-medium text-emerald-600 dark:text-emerald-400" data-testid="save-success">
              ✓ {t("editor.saved", "Saved successfully")}
            </span>
          )}
          {saveError && !invalidJson && (
            <span className="text-xs font-medium text-destructive" data-testid="save-error">
              {saveError}
            </span>
          )}
          {blockingReason && !readOnly && isDirty && (
            <span className="text-xs font-medium text-destructive" role="alert" data-testid="save-blocked-reason">
              {blockingReason}
            </span>
          )}
          {invalidJson && (
            <span className="text-xs font-medium text-destructive" role="alert" data-testid="save-invalid-json">
              {t("editor.invalidJsonNotSaved", "Not saved — the JSON is invalid: {{reason}}", {
                reason: invalidJson,
              })}
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
                disabled={!isDirty || isSaving || isSaveAndDeploying || !!blockingReason}
                title={blockingReason ?? undefined}
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
                  disabled={!isDirty || isSaving || isSaveAndDeploying || !!blockingReason}
                  title={blockingReason ?? undefined}
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

      {saveWarnings && saveWarnings.length > 0 && dismissedWarnings !== saveWarnings && !isDirty && (
        <div
          className="rounded-lg border border-warn-300 bg-warn-50 p-3 text-sm text-warn-900 dark:border-warn-800 dark:bg-warn-900/20 dark:text-warn-200"
          role="status"
          data-testid="save-warnings"
        >
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-medium">{t("editor.savedWithWarnings", "Saved, with warnings")}</p>
              <ul className="mt-1 list-disc ps-5">
                {saveWarnings.map((w, i) => (
                  <li key={i}>{w}</li>
                ))}
              </ul>
            </div>
            <button
              type="button"
              onClick={() => setDismissedWarnings(saveWarnings)}
              className="text-xs underline"
              data-testid="save-warnings-dismiss"
            >
              {t("editor.dismissWarnings", "Dismiss")}
            </button>
          </div>
        </div>
      )}

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
                  return renderFormEditor(parsed, handleFormChange, readOnly, {
                    resourceId,
                    version: currentVersion,
                    isDirty,
                  });
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
    </EditorValidityContext.Provider>
  );
}
