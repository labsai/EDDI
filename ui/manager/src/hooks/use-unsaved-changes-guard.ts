import { useEffect } from "react";

/**
 * Prevent accidental data loss when there are unsaved changes.
 *
 * Uses the browser's `beforeunload` event to show a native prompt when
 * the user tries to close the tab, reload, or navigate to an external URL.
 *
 * NOTE: React Router's `useBlocker` requires `createBrowserRouter` (data router API).
 * This app uses `<BrowserRouter>`, so we rely on `beforeunload` only.
 * In-app navigation confirmation is handled via the Discard button and
 * explicit "are you sure?" prompts in the UI.
 *
 * @param isDirty Whether there are unsaved changes
 */
export function useUnsavedChangesGuard(isDirty: boolean) {
  useEffect(() => {
    if (!isDirty) return;

    function handleBeforeUnload(e: BeforeUnloadEvent) {
      e.preventDefault();
      // Modern browsers ignore custom messages but still show a native prompt
      e.returnValue = "";
    }

    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [isDirty]);
}
