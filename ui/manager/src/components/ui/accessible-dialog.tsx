import { useEffect, useRef, useCallback, useId, type ReactNode } from "react";
import { X } from "lucide-react";
import { useTranslation } from "react-i18next";

interface AccessibleDialogProps {
  /** Whether the dialog is open */
  open: boolean;
  /** Called when dialog should close */
  onClose: () => void;
  /** Dialog title (also used for aria-labelledby) */
  title: string;
  /** Dialog content */
  children: ReactNode;
  /** Optional data-testid */
  testId?: string;
  /** Max width class (default: max-w-md) */
  maxWidth?: string;
  /** Whether to show the close button (default: true) */
  showClose?: boolean;
}

/**
 * Accessible modal dialog with:
 * - role="dialog" + aria-modal="true"
 * - aria-labelledby pointing to the title
 * - Focus trapping (Tab cycles within dialog)
 * - Escape key to close
 * - Return focus to trigger element on close
 * - Backdrop click to close
 */
export function AccessibleDialog({
  open,
  onClose,
  title,
  children,
  testId,
  maxWidth = "max-w-md",
  showClose = true,
}: AccessibleDialogProps) {
  const { t } = useTranslation();
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = useId();

  // The element to hand focus back to on close, recorded while rendering the
  // opening render — before React commits the dialog. Any effect is too late:
  // an `autoFocus` field inside the dialog has taken focus by then, and
  // "returning" focus to it after close sends it to <body>.
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const wasOpenRef = useRef(false);
  if (open && !wasOpenRef.current) {
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }
  wasOpenRef.current = open;

  // Focus the dialog on open, and return focus to the trigger on close.
  useEffect(() => {
    if (!open) return;
    // Focus the dialog after render — unless focus is already inside it. An
    // `autoFocus` field has claimed it during commit, and a user can click
    // into a field before the frame runs; moving either to the first focusable
    // (usually the Close button) sent their typing nowhere. Under a loaded CI
    // runner the late frame did exactly that mid-`userEvent.type`.
    const frame = requestAnimationFrame(() => {
      const dialog = dialogRef.current;
      if (!dialog || dialog.contains(document.activeElement)) return;
      dialog
        .querySelector<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')
        ?.focus();
    });
    // A cleanup rather than an `open === false` branch: several callers mount
    // the dialog already open and unmount it to close (`{target && <ShareDialog
    // open …/>}`), and an unmount never renders `open={false}`. Restore only if
    // focus was actually lost with the dialog's DOM — StrictMode runs this
    // cleanup once on mount with the dialog still up and focus inside it.
    return () => {
      cancelAnimationFrame(frame);
      const target = previousFocusRef.current;
      const active = document.activeElement;
      if (target?.isConnected && (!active || active === document.body)) target.focus();
    };
  }, [open]);

  // Escape key handler
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      // A control inside the dialog that consumed Escape itself — an open
      // AgentPicker or combobox closing its popup — marks it handled. Closing
      // the whole dialog on top of that threw away the form the user was
      // filling in (Trigger, Edit Grant).
      if (e.key === "Escape" && !e.defaultPrevented) {
        e.stopPropagation();
        onClose();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

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
      {/* Backdrop — visual only. The centring layer below covers it entirely,
          so a click handler here never fired; the layer owns the click. */}
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        aria-hidden="true"
      />

      {/* Dialog */}
      <div
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
        data-testid={testId ? `${testId}-backdrop` : undefined}
        onClick={(e) => {
          // Only a click on the dimmed area itself, not one that bubbled out of
          // the dialog box.
          if (e.target === e.currentTarget) onClose();
        }}
      >
        {/* Capped at the viewport and scrolled in the body, not the box: a
            dialog taller than the window was centred and then clipped at BOTH
            ends, so its title and close button sat off-screen with nothing to
            scroll — the picker with a full list hit this at 900×480. The
            header stays put; only the content moves. `dvh`, so a mobile URL
            bar sliding in does not re-clip it. */}
        <div
          ref={dialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          className={`flex max-h-[calc(100dvh-2rem)] w-full ${maxWidth} flex-col rounded-xl border bg-card shadow-2xl`}
          onClick={(e) => e.stopPropagation()}
          onKeyDown={handleKeyDown}
          data-testid={testId}
        >
          {/* Header */}
          <div className="flex shrink-0 items-center justify-between border-b border-border p-5">
            <h2
              id={titleId}
              className="text-lg font-semibold text-foreground"
            >
              {title}
            </h2>
            {showClose && (
              <button
                onClick={onClose}
                aria-label={t("common.close", "Close")}
                className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
              >
                <X className="h-5 w-5" />
              </button>
            )}
          </div>

          {/* Content — `min-h-0` so this is what shrinks and scrolls when the
              dialog meets the viewport cap, rather than overflowing the box. */}
          <div className="min-h-0 overflow-y-auto">{children}</div>
        </div>
      </div>
    </>
  );
}
