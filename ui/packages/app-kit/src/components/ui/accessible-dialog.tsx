import { useEffect, useRef, useCallback, useId, type ReactNode } from "react";
import { X } from "lucide-react";
import { useTranslation } from "react-i18next";

/**
 * The open dialogs, oldest first. Escape closes the top-most one only: every
 * dialog listens on `window`, so two stacked dialogs (a picker's create dialog
 * over the form that hosts it) used to close together on one key press.
 */
const openDialogs: string[] = [];

interface AccessibleDialogProps {
  /** Whether the dialog is open */
  open: boolean;
  /** Called when dialog should close */
  onClose: () => void;
  /** Dialog title (also used for aria-labelledby) */
  title: string;
  /**
   * Dialog content. Rendered edge to edge — the dialog adds no padding of its
   * own, so a full-bleed body (a diff editor, a toolbar strip) can reach the
   * border. Ordinary content brings its own: wrap it in `p-5`, matching the
   * header, or it sits flush against the dialog's border.
   */
  children: ReactNode;
  /** Optional data-testid */
  testId?: string;
  /** Max width class (default: max-w-md) */
  maxWidth?: string;
  /** Whether to show the close button (default: true) */
  showClose?: boolean;
  /** Optional data-testid of the close button. */
  closeTestId?: string;
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
  closeTestId,
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

  // The latest close callback, read when Escape or the backdrop fires. Kept out
  // of the stack effect below: a parent handing a new `onClose` to a LOWER
  // dialog re-ran that effect, which took the dialog off the stack and pushed it
  // back on top — Escape then closed it underneath the one the user was in.
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  // Escape key handler. Stack membership changes only when the dialog opens or closes.
  useEffect(() => {
    if (!open) return;
    openDialogs.push(titleId);
    const handleKeyDown = (e: KeyboardEvent) => {
      // A dialog stacked on top (the unsaved-changes prompt) handles Escape in
      // the capture phase and marks it handled — leave that one to it. Among
      // AccessibleDialogs only the top-most one answers.
      if (openDialogs[openDialogs.length - 1] !== titleId) return;
      if (e.key === "Escape" && !e.defaultPrevented) {
        e.stopPropagation();
        onCloseRef.current();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => {
      window.removeEventListener("keydown", handleKeyDown);
      const index = openDialogs.lastIndexOf(titleId);
      if (index >= 0) openDialogs.splice(index, 1);
    };
  }, [open, titleId]);

  // A click on the area around the panel closes the dialog like the backdrop
  // under it — but only a click that also STARTED there: a text selection
  // dragged out of a field ends its click on this container, and must not
  // close the dialog and lose what was typed.
  const pressStartedOutsideRef = useRef(false);

  // Focus trap
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;

      // Only what Tab can actually reach: a disabled Save as the "last" stop let
      // Tab out of the dialog and sent Shift+Tab to a control that cannot take focus.
      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'),
      ).filter((el) => !el.matches(":disabled") && el.tabIndex >= 0 && !el.closest("[inert], [aria-hidden='true']"));
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
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Dialog */}
      <div
        className="fixed inset-0 z-50 flex items-center justify-center p-4"
        onMouseDown={(e) => {
          pressStartedOutsideRef.current = e.target === e.currentTarget;
        }}
        onClick={(e) => {
          const outside = e.target === e.currentTarget && pressStartedOutsideRef.current;
          pressStartedOutsideRef.current = false;
          if (outside) onCloseRef.current();
        }}
        data-testid={testId ? `${testId}-overlay` : undefined}
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
                data-testid={closeTestId}
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
