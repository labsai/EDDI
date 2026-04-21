import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Workflow, Bot, RefreshCw, Check, X } from "lucide-react";
import type { ResourceUsage } from "@/lib/api/resource-usage";

export interface UpdateUsageDialogProps {
  /** List of workflows/agents using this resource */
  usages: ResourceUsage[];
  /** Whether cascade update is in progress */
  isUpdating: boolean;
  /** Called when user confirms which items to cascade */
  onConfirm: (selected: ResourceUsage[]) => void;
  /** Called when user dismisses the dialog */
  onDismiss: () => void;
}

/**
 * Post-save dialog showing which workflows/agents use the saved config.
 * User can select which to cascade-update to the new version.
 */
export function UpdateUsageDialog({
  usages,
  isUpdating,
  onConfirm,
  onDismiss,
}: UpdateUsageDialogProps) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<Set<number>>(
    new Set(usages.map((_, i) => i))
  );

  function toggleItem(index: number) {
    const next = new Set(selected);
    if (next.has(index)) next.delete(index);
    else next.add(index);
    setSelected(next);
  }

  function handleConfirm() {
    const items = usages.filter((_, i) => selected.has(i));
    onConfirm(items);
  }

  if (usages.length === 0) return null;

  return (
    <div
      className="rounded-xl border border-amber-200 bg-amber-50/80 p-4 dark:border-amber-800 dark:bg-amber-950/30"
      data-testid="update-usage-dialog"
    >
      <div className="mb-3 flex items-center gap-2">
        <Workflow className="h-5 w-5 text-amber-600 dark:text-amber-400" />
        <h3 className="text-sm font-semibold text-amber-900 dark:text-amber-200">
          {t("editor.usedInAgents", {
            count: usages.length,
            defaultValue: "Used in {{count}} agent(s)",
          })}
        </h3>
      </div>
      <p className="mb-3 text-xs text-amber-700 dark:text-amber-300">
        {t(
          "editor.updateUsageHint",
          "Update the selected workflows and agents to use the new version?"
        )}
      </p>
      <ul className="mb-3 space-y-2">
        {usages.map((usage, i) => (
          <li key={`${usage.agentId}-${usage.workflowId}`}>
            <label className="flex cursor-pointer items-center gap-2 rounded-lg border border-amber-200 bg-white/60 px-3 py-2 transition-colors hover:bg-white/80 dark:border-amber-800 dark:bg-amber-950/20 dark:hover:bg-amber-950/40">
              <input
                type="checkbox"
                checked={selected.has(i)}
                onChange={() => toggleItem(i)}
                disabled={isUpdating}
                className="h-4 w-4 rounded border-amber-300 accent-amber-600"
                data-testid={`usage-checkbox-${i}`}
              />
              <div className="flex flex-1 items-center gap-3 text-xs">
                <span className="flex items-center gap-1 text-amber-800 dark:text-amber-300">
                  <Bot className="h-3.5 w-3.5" />
                  {usage.agentName} <span className="opacity-50">v{usage.agentVersion}</span>
                </span>
                <span className="text-amber-500">→</span>
                <span className="flex items-center gap-1 text-amber-800 dark:text-amber-300">
                  <Workflow className="h-3.5 w-3.5" />
                  {usage.workflowName} <span className="opacity-50">v{usage.workflowVersion}</span>
                </span>
              </div>
            </label>
          </li>
        ))}
      </ul>
      <div className="flex gap-2">
        <button
          onClick={handleConfirm}
          disabled={isUpdating || selected.size === 0}
          className="inline-flex items-center gap-1.5 rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-medium text-white shadow-sm transition-all hover:bg-amber-700 active:scale-[0.98] disabled:opacity-50 dark:bg-amber-500 dark:hover:bg-amber-600"
          data-testid="confirm-cascade-btn"
        >
          {isUpdating ? (
            <RefreshCw className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Check className="h-3.5 w-3.5" />
          )}
          {isUpdating
            ? t("editor.cascading", "Updating...")
            : t("editor.updateUsage", "Update Selected")}
        </button>
        <button
          onClick={onDismiss}
          disabled={isUpdating}
          className="inline-flex items-center gap-1.5 rounded-lg border border-amber-200 px-3 py-1.5 text-xs font-medium text-amber-800 transition-all hover:bg-amber-100 active:scale-[0.98] disabled:opacity-50 dark:border-amber-800 dark:text-amber-300 dark:hover:bg-amber-950/40"
          data-testid="dismiss-cascade-btn"
        >
          <X className="h-3.5 w-3.5" />
          {t("editor.skipUpdate", "Skip")}
        </button>
      </div>
    </div>
  );
}
