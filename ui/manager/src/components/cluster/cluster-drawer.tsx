import { useEffect, useId, useRef, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";

// Disabled controls cannot take focus: counting one as the first or last stop made
// Tab step out of the dialog when the last control (say a disabled "Replay") was disabled.
const FOCUSABLE =
  'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';

/**
 * A side sheet for details: modal (focus is trapped inside, Escape and the
 * backdrop close it) and focus returns to the element that opened it. Slides in
 * from the inline end, so it sits on the left in Arabic.
 */
export function ClusterDrawer({
  open,
  onClose,
  title,
  children,
  testId,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  testId?: string;
}) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const returnTo = useRef<HTMLElement | null>(null);
  const wasOpen = useRef(false);
  if (open && !wasOpen.current) {
    returnTo.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  }
  wasOpen.current = open;
  // Callers pass an inline arrow; depending on it re-ran the effect below on every
  // parent render (each data refresh), whose cleanup returned focus to the opener
  // behind the dialog and then pulled it back to the first control.
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    if (!open) return;
    const frame = requestAnimationFrame(() => {
      const panel = ref.current;
      if (panel && !panel.contains(document.activeElement)) panel.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onCloseRef.current();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("keydown", onKey);
      const target = returnTo.current;
      if (target?.isConnected) target.focus();
    };
  }, [open]);

  if (!open) return null;

  const trap = (e: React.KeyboardEvent) => {
    if (e.key !== "Tab" || !ref.current) return;
    const items = ref.current.querySelectorAll<HTMLElement>(FOCUSABLE);
    if (items.length === 0) return;
    const first = items[0]!;
    const last = items[items.length - 1]!;
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/40" onClick={onClose} aria-hidden="true" />
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={trap}
        className="fixed inset-y-0 end-0 z-50 flex w-full max-w-xl flex-col border-s border-border bg-card shadow-2xl"
        data-testid={testId}
      >
        <div className="flex shrink-0 items-center justify-between border-b border-border p-5">
          <h2 id={titleId} className="text-lg font-semibold text-foreground">
            {title}
          </h2>
          <button
            onClick={onClose}
            aria-label={t("common.close", "Close")}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
            data-testid="cluster-drawer-close"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto p-5">{children}</div>
      </div>
    </>
  );
}
