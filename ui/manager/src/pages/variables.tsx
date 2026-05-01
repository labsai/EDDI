import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Variable,
  Plus,
  Trash2,
  Loader2,
  X,
  Info,
  Copy,
  Pencil,
  Check,
  XCircle,
  Search,
  Package,
} from "lucide-react";
import {
  useVariables,
  useUpsertVariable,
  useDeleteVariable,
} from "@/hooks/use-variables";
import { isValidVariableKey } from "@/lib/api/variables";
import type { GlobalVariable } from "@/lib/api/variables";

/* ─── Key validation pattern (for display) ─── */
const KEY_HINT = "Letters, digits, dots, underscores, hyphens only.";

export function VariablesPage() {
  const { t } = useTranslation();

  /* ─── Dialog state ─── */
  const [showDialog, setShowDialog] = useState(false);
  const [editMode, setEditMode] = useState(false);
  const [formKey, setFormKey] = useState("");
  const [formValue, setFormValue] = useState("");
  const [formDescription, setFormDescription] = useState("");
  const [formExportable, setFormExportable] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<GlobalVariable | null>(null);
  const [searchQuery, setSearchQuery] = useState("");

  /* ─── Queries ─── */
  const { data: variables, isLoading } = useVariables();
  const upsertMut = useUpsertVariable();
  const deleteMut = useDeleteVariable();

  /* ─── Derived ─── */
  const keyValid = formKey.trim().length > 0 && isValidVariableKey(formKey.trim());
  const keyTouched = formKey.trim().length > 0;
  const canSubmit = keyValid && formValue.trim().length > 0;

  const filteredVariables = useMemo(() => {
    if (!variables) return [];
    if (!searchQuery.trim()) return variables;
    const q = searchQuery.toLowerCase();
    return variables.filter(
      (v) =>
        v.key.toLowerCase().includes(q) ||
        v.value.toLowerCase().includes(q) ||
        (v.description ?? "").toLowerCase().includes(q),
    );
  }, [variables, searchQuery]);

  /* ─── Copy reference ─── */
  const copyRef = useCallback(
    (key: string, syntax: "eddivar" | "vars") => {
      const ref =
        syntax === "eddivar"
          ? `\${eddivar:${key}}`
          : `{{vars.${key}}}`;
      navigator.clipboard.writeText(ref).then(
        () => {
          toast.success(
            t("variables.refCopied", {
              ref,
              defaultValue: `Copied: ${ref}`,
            }),
          );
        },
        () => {
          toast.error(t("common.copyFailed", "Failed to copy to clipboard"));
        },
      );
    },
    [t],
  );

  /* ─── Open create dialog ─── */
  const openCreate = useCallback(() => {
    setEditMode(false);
    setFormKey("");
    setFormValue("");
    setFormDescription("");
    setFormExportable(true);
    setShowDialog(true);
  }, []);

  /* ─── Open edit dialog ─── */
  const openEdit = useCallback((v: GlobalVariable) => {
    setEditMode(true);
    setFormKey(v.key);
    setFormValue(v.value);
    setFormDescription(v.description ?? "");
    setFormExportable(v.exportable ?? true);
    setShowDialog(true);
  }, []);

  /* ─── Close dialog ─── */
  const closeDialog = useCallback(() => {
    setShowDialog(false);
    setFormKey("");
    setFormValue("");
    setFormDescription("");
    setFormExportable(true);
  }, []);

  /* ─── Submit create/edit ─── */
  const handleSubmit = useCallback(() => {
    if (!canSubmit) return;
    const variable: GlobalVariable = {
      key: formKey.trim(),
      value: formValue.trim(),
      description: formDescription.trim() || undefined,
      exportable: formExportable,
    };
    upsertMut.mutate(
      { key: variable.key, variable },
      {
        onSuccess: () => {
          toast.success(
            t("variables.saveSuccess", {
              key: variable.key,
              defaultValue: `Variable "${variable.key}" saved`,
            }),
          );
          closeDialog();
        },
        onError: (err) =>
          toast.error(err instanceof Error ? err.message : String(err)),
      },
    );
  }, [canSubmit, formKey, formValue, formDescription, formExportable, upsertMut, t, closeDialog]);

  /* ─── Delete handler ─── */
  const handleDelete = useCallback(() => {
    if (!deleteTarget) return;
    deleteMut.mutate(deleteTarget.key, {
      onSuccess: () => {
        toast.success(
          t("variables.deleteSuccess", {
            key: deleteTarget.key,
            defaultValue: `Variable "${deleteTarget.key}" deleted`,
          }),
        );
        setDeleteTarget(null);
      },
      onError: (err) =>
        toast.error(err instanceof Error ? err.message : String(err)),
    });
  }, [deleteTarget, deleteMut, t]);

  /* ─── Truncate helper ─── */
  const truncate = (s: string, max: number) =>
    s.length > max ? s.substring(0, max) + "…" : s;

  return (
    <div className="space-y-6 p-6" data-testid="variables-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            {t("variables.title", "Global Variables")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "variables.description",
              "Deployment-wide configuration values available in all agents.",
            )}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={openCreate}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
            data-testid="create-variable-button"
          >
            <Plus className="h-4 w-4" />
            {t("variables.create", "Add Variable")}
          </button>
        </div>
      </div>

      {/* Info box */}
      <div className="flex items-start gap-2.5 rounded-lg border border-primary/20 bg-primary/5 px-4 py-3">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
        <div className="space-y-1.5">
          <p className="text-xs text-muted-foreground">
            {t(
              "variables.infoLine1",
              "Use {{vars.<key>}} in system prompts or ${eddivar:<key>} anywhere in agent configurations.",
            )}
          </p>
          <p className="text-xs text-muted-foreground">
            {t(
              "variables.infoLine2",
              "Changes take effect within 2 minutes (cache TTL). For sensitive values, use the Secrets Vault instead.",
            )}
          </p>
        </div>
      </div>

      {/* Search bar */}
      {variables && variables.length > 0 && (
        <div className="flex items-center gap-2">
          <div className="relative max-w-sm flex-1">
            <Search className="absolute inset-s-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t("variables.searchPlaceholder", "Filter variables…")}
              className="h-9 w-full rounded-lg border border-input bg-background ps-9 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
              data-testid="variables-search"
            />
          </div>
          {isLoading && (
            <div className="flex h-9 items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-4 w-4 animate-spin" />
              {t("common.loading", "Loading…")}
            </div>
          )}
        </div>
      )}

      {/* Loading state (no data yet) */}
      {isLoading && !variables && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      )}

      {/* Variables table */}
      {filteredVariables.length > 0 && (
        <div className="rounded-xl border border-border bg-card shadow-sm">
          <div className="border-b border-border px-4 py-3">
            <h2 className="text-sm font-semibold text-foreground">
              {t("variables.tableTitle", {
                count: filteredVariables.length,
                defaultValue: `${filteredVariables.length} variable${filteredVariables.length === 1 ? "" : "s"}`,
              })}
            </h2>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-start">
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    {t("variables.colKey", "Key")}
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    {t("variables.colValue", "Value")}
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    {t("variables.colDescription", "Description")}
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Package className="h-3.5 w-3.5" />
                      {t("variables.colExportable", "Export")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    {t("variables.colReference", "Reference")}
                  </th>
                  <th className="px-4 py-3 text-end font-semibold text-foreground">
                    {t("variables.colActions", "Actions")}
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filteredVariables.map((v) => (
                  <tr
                    key={v.key}
                    className="transition-colors hover:bg-muted/50"
                    data-testid={`variable-row-${v.key}`}
                  >
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-2">
                        <Variable className="h-4 w-4 text-primary" />
                        <span className="font-mono text-sm font-medium text-foreground">
                          {v.key}
                        </span>
                      </span>
                    </td>
                    <td className="max-w-48 px-4 py-3 text-muted-foreground">
                      <span className="line-clamp-1 font-mono text-xs" title={v.value}>
                        {truncate(v.value, 40)}
                      </span>
                    </td>
                    <td className="max-w-48 px-4 py-3 text-muted-foreground">
                      <span className="line-clamp-1" title={v.description ?? undefined}>
                        {v.description || "—"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {(v.exportable ?? true) ? (
                        <Check
                          className="h-4 w-4 text-emerald-500"
                          aria-label={t("variables.exportYes", "Included in exports")}
                          data-testid={`export-yes-${v.key}`}
                        />
                      ) : (
                        <XCircle
                          className="h-4 w-4 text-muted-foreground/50"
                          aria-label={t("variables.exportNo", "Excluded from exports")}
                          data-testid={`export-no-${v.key}`}
                        />
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1">
                        <button
                          onClick={() => copyRef(v.key, "eddivar")}
                          className="inline-flex items-center gap-1 rounded-md bg-muted/50 px-2 py-1 font-mono text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                          title={t("variables.copyEddivar", "Copy ${eddivar:…} reference")}
                          data-testid={`copy-eddivar-${v.key}`}
                        >
                          <Copy className="h-3 w-3" />
                          {`\${eddivar:${v.key}}`}
                        </button>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-end">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => openEdit(v)}
                          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/10"
                          data-testid={`edit-${v.key}`}
                          aria-label={t("variables.editKey", {
                            key: v.key,
                            defaultValue: `Edit ${v.key}`,
                          })}
                        >
                          <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
                          {t("common.edit", "Edit")}
                        </button>
                        <button
                          onClick={() => setDeleteTarget(v)}
                          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10"
                          data-testid={`delete-${v.key}`}
                          aria-label={t("variables.deleteKey", {
                            key: v.key,
                            defaultValue: `Delete ${v.key}`,
                          })}
                        >
                          <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                          {t("common.delete", "Delete")}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Empty state */}
      {variables && variables.length === 0 && (
        <div
          className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border p-12 text-center"
          data-testid="variables-empty"
        >
          <Variable className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-lg font-medium text-foreground">
            {t("variables.empty", "No global variables defined")}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "variables.emptyHint",
              "Click \"Add Variable\" to create your first deployment-wide configuration value.",
            )}
          </p>
        </div>
      )}

      {/* Search yielded no results */}
      {variables && variables.length > 0 && filteredVariables.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border p-12 text-center">
          <Search className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-lg font-medium text-foreground">
            {t("common.noResults", "No results found")}
          </p>
        </div>
      )}

      {/* ─── Create / Edit dialog ─── */}
      {showDialog && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={closeDialog}
          onKeyDown={(e) => {
            if (e.key === "Escape") closeDialog();
          }}
        >
          <div
            className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="variable-dialog-title"
          >
            <div className="flex items-center justify-between">
              <h2
                id="variable-dialog-title"
                className="text-lg font-semibold text-foreground"
              >
                {editMode
                  ? t("variables.editTitle", "Edit Variable")
                  : t("variables.createTitle", "Add Global Variable")}
              </h2>
              <button
                onClick={closeDialog}
                className="rounded-lg p-1.5 text-muted-foreground hover:bg-muted"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="mt-4 space-y-4">
              {/* Key */}
              <div>
                <label
                  htmlFor="var-key"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("variables.keyLabel", "Key")} *
                </label>
                <input
                  id="var-key"
                  type="text"
                  value={formKey}
                  onChange={(e) => setFormKey(e.target.value)}
                  disabled={editMode}
                  placeholder={t(
                    "variables.keyPlaceholder",
                    "e.g., default-model",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background px-3 font-mono text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50 disabled:cursor-not-allowed disabled:opacity-60"
                  data-testid="var-key-input"
                  autoComplete="off"
                  autoFocus={!editMode}
                />
                {keyTouched && !keyValid && (
                  <p className="mt-1 text-xs text-destructive" data-testid="key-error">
                    {t("variables.keyError", KEY_HINT)}
                  </p>
                )}
                {!editMode && (
                  <p className="mt-1 text-[10px] text-muted-foreground">
                    {KEY_HINT}
                  </p>
                )}
              </div>

              {/* Value */}
              <div>
                <label
                  htmlFor="var-value"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("variables.valueLabel", "Value")} *
                </label>
                <input
                  id="var-value"
                  type="text"
                  value={formValue}
                  onChange={(e) => setFormValue(e.target.value)}
                  placeholder={t(
                    "variables.valuePlaceholder",
                    "e.g., gpt-4.1",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                  data-testid="var-value-input"
                  autoComplete="off"
                  autoFocus={editMode}
                />
                <p className="mt-1 flex items-center gap-1 text-[10px] text-amber-600 dark:text-amber-400">
                  <Info className="h-3 w-3 shrink-0" />
                  {t(
                    "variables.valueWarning",
                    "Values are visible in UI and logs. Use the Secrets Vault for sensitive data.",
                  )}
                </p>
              </div>

              {/* Description */}
              <div>
                <label
                  htmlFor="var-description"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("variables.descriptionLabel", "Description (optional)")}
                </label>
                <input
                  id="var-description"
                  type="text"
                  value={formDescription}
                  onChange={(e) => setFormDescription(e.target.value)}
                  placeholder={t(
                    "variables.descriptionPlaceholder",
                    "Optional description…",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                  data-testid="var-description-input"
                  autoComplete="off"
                />
              </div>

              {/* Exportable checkbox */}
              <label className="flex items-center gap-2 text-sm text-foreground">
                <input
                  type="checkbox"
                  checked={formExportable}
                  onChange={(e) => setFormExportable(e.target.checked)}
                  className="h-4 w-4 rounded border-input accent-primary"
                  data-testid="var-exportable-checkbox"
                />
                {t("variables.exportableLabel", "Include in agent exports")}
              </label>
            </div>

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={closeDialog}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                onClick={handleSubmit}
                disabled={!canSubmit || upsertMut.isPending}
                className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                data-testid="confirm-save-button"
              >
                {upsertMut.isPending && (
                  <Loader2 className="h-4 w-4 animate-spin" />
                )}
                {t("variables.save", "Save Variable")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ─── Delete confirmation dialog ─── */}
      {deleteTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={() => setDeleteTarget(null)}
          onKeyDown={(e) => {
            if (e.key === "Escape") setDeleteTarget(null);
          }}
        >
          <div
            className="w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="delete-variable-title"
            aria-describedby="delete-variable-desc"
          >
            <h2
              id="delete-variable-title"
              className="text-lg font-semibold text-foreground"
            >
              {t("variables.confirmDeleteTitle", "Delete Variable")}
            </h2>
            <p
              id="delete-variable-desc"
              className="mt-2 text-sm text-muted-foreground"
            >
              {t("variables.confirmDeleteMessage", {
                key: deleteTarget.key,
                defaultValue: `Are you sure you want to delete "${deleteTarget.key}"? Agents using \${eddivar:${deleteTarget.key}} will see unresolved references.`,
              })}
            </p>
            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setDeleteTarget(null)}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                onClick={handleDelete}
                disabled={deleteMut.isPending}
                className="inline-flex items-center gap-2 rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground transition-colors hover:bg-destructive/90 disabled:opacity-50"
                data-testid="confirm-delete-button"
              >
                {deleteMut.isPending && (
                  <Loader2 className="h-4 w-4 animate-spin" />
                )}
                {t("common.delete", "Delete")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
