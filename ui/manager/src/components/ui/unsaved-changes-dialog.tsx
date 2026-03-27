import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";

interface UnsavedChangesDialogProps {
  open: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  /** Message to display. Defaults to "You have unsaved changes..." */
  message?: string;
  /** Title. Defaults to "Unsaved Changes" */
  title?: string;
}

/**
 * Confirmation dialog shown when user tries to leave with unsaved changes
 * or clicks the Discard button.
 */
export function UnsavedChangesDialog({
  open,
  onConfirm,
  onCancel,
  message,
  title,
}: UnsavedChangesDialogProps) {
  const { t } = useTranslation();

  // Esc key cancels
  useEffect(() => {
    if (!open) return;
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, onCancel]);

  if (!open) return null;

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/50" onClick={onCancel} />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div className="w-full max-w-sm rounded-xl border bg-card shadow-2xl">
          <div className="flex items-center gap-3 border-b border-border p-5">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-amber-100 dark:bg-amber-900/30">
              <AlertTriangle className="h-5 w-5 text-amber-600 dark:text-amber-400" />
            </div>
            <h2 className="text-lg font-semibold text-foreground">
              {title ?? t("editor.unsavedTitle", "Unsaved Changes")}
            </h2>
          </div>

          <div className="p-5">
            <p className="text-sm text-muted-foreground">
              {message ??
                t(
                  "editor.unsavedMessage",
                  "You have unsaved changes that will be lost. Are you sure you want to continue?"
                )}
            </p>
          </div>

          <div className="flex justify-end gap-2 border-t border-border p-4">
            <button
              onClick={onCancel}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
              data-testid="unsaved-cancel"
            >
              {t("common.cancel", "Cancel")}
            </button>
            <button
              onClick={onConfirm}
              className="rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors"
              data-testid="unsaved-confirm"
            >
              {t("editor.discardAndLeave", "Discard & Leave")}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
