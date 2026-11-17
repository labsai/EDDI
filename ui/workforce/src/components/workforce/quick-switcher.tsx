import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { NEW_CHAT_STATE, OPEN_SWITCHER_EVENT } from "@/lib/workforce-keyboard";

/**
 * Workforce's keyboard entry points (T1.10):
 *
 * - ⌘K / Ctrl+K opens the quick switcher — jump to any chat or team session in
 *   the sidebar, open a chat with an agent, or start a new chat.
 * - ⌘⇧O / Ctrl+Shift+O starts a new chat: Home, with its composer focused.
 *
 * Both work from inside a text field too, as in other chat apps. The dialog
 * (and the session reads it needs) is a lazy chunk: nothing of it is in the
 * first load until someone asks for it.
 */
const QuickSwitcherDialog = lazy(() =>
  import("./quick-switcher-dialog").then((m) => ({ default: m.QuickSwitcherDialog })),
);

export function QuickSwitcher() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  /** Where focus was when the switcher opened; it goes back there on close. */
  const returnFocus = useRef<HTMLElement | null>(null);

  const show = useCallback(() => {
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setOpen(true);
  }, []);

  const close = useCallback((restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) requestAnimationFrame(() => returnFocus.current?.focus());
  }, []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || e.isComposing) return;
      const key = e.key.toLowerCase();
      if (key === "k" && !e.shiftKey) {
        e.preventDefault();
        if (open) close(true);
        else show();
      } else if (key === "o" && e.shiftKey) {
        e.preventDefault();
        setOpen(false);
        navigate("/workforce", { state: NEW_CHAT_STATE });
      }
    };
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener(OPEN_SWITCHER_EVENT, show);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener(OPEN_SWITCHER_EVENT, show);
    };
  }, [open, show, close, navigate]);

  if (!open) return null;
  return (
    <Suspense fallback={null}>
      <QuickSwitcherDialog onClose={close} />
    </Suspense>
  );
}
