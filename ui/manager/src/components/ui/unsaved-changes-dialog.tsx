import { useEffect, useRef, useCallback } from "react";
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
  /** Label of the button that stays. Defaults to "Cancel" */
  cancelLabel?: string;
  /** Label of the destructive button. Defaults to "Discard & Leave" */
  confirmLabel?: string;
  /** Offers a primary "Save & leave" button when given */
  onSave?: () => void;
  /** Disables the buttons while `onSave` runs */
  isSaving?: boolean;
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
  cancelLabel,
  confirmLabel,
  onSave,
  isSaving = false,
}: UnsavedChangesDialogProps) {
  const { t } = useTranslation();
  const dialogRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  // Store previous focus + focus dialog on open
  useEffect(() => {
    if (open) {
      previousFocusRef.current = document.activeElement as HTMLElement;
      requestAnimationFrame(() => {
        dialogRef.current
          ?.querySelector<HTMLElement>("button")
          ?.focus();
      });
    } else {
      previousFocusRef.current?.focus();
    }
  }, [open]);

  // Esc key cancels
  useEffect(() => {
    if (!open) return;
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, onCancel]);

  // Focus trap
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;

      const focusable = dialog.querySelectorAll<HTMLElement>(
        'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
      );
      if (focusable.length === 0) return;

      const first = focusable[0]!;
      const last = focusable[focusable.length - 1]!;

      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    },
    []
  );

  if (!open) return null;

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/50" onClick={onCancel} aria-hidden="true" />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          ref={dialogRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="unsaved-dialog-title"
          aria-describedby="unsaved-dialog-desc"
          className="w-full max-w-md rounded-xl border bg-card shadow-2xl"
          onKeyDown={handleKeyDown}
        >
          <div className="flex items-center gap-3 border-b border-border p-5">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-warning/10">
              <AlertTriangle className="h-5 w-5 text-warning" aria-hidden="true" />
            </div>
            <h2 id="unsaved-dialog-title" className="text-lg font-semibold text-foreground">
              {title ?? t("editor.unsavedTitle", "Unsaved Changes")}
            </h2>
          </div>

          <div className="p-5">
            <p id="unsaved-dialog-desc" className="text-sm text-muted-foreground">
              {message ??
                t(
                  "editor.unsavedMessage",
                  "You have unsaved changes that will be lost. Are you sure you want to continue?"
                )}
            </p>
          </div>

          <div className="flex flex-wrap justify-end gap-2 border-t border-border p-4">
            <button
              onClick={onCancel}
              disabled={isSaving}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
              data-testid="unsaved-cancel"
            >
              {cancelLabel ?? t("common.cancel", "Cancel")}
            </button>
            <button
              onClick={onConfirm}
              disabled={isSaving}
              className={
                // With a Save button beside it, Discard is the secondary choice.
                onSave
                  ? "rounded-lg bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 transition-colors disabled:opacity-50"
                  : "rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50"
              }
              data-testid="unsaved-confirm"
            >
              {confirmLabel ?? t("editor.discardAndLeave", "Discard & Leave")}
            </button>
            {onSave && (
              <button
                onClick={onSave}
                disabled={isSaving}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                data-testid="unsaved-save"
              >
                {isSaving ? t("editor.saving", "Saving...") : t("editor.saveAndLeave", "Save & leave")}
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );
}
