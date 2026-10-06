import { useEffect, useRef } from "react";
import { registerGuard, type UnsavedGuard } from "@/lib/unsaved-changes-registry";

/**
 * Prevent accidental data loss when there are unsaved changes.
 *
 * Two layers:
 *  - the browser's `beforeunload` prompt, for closing the tab, reloading or
 *    leaving the app;
 *  - in-app navigation — a link anywhere in the app (sidebar, breadcrumbs, a
 *    back link), the back/forward buttons, and programmatic navigation routed
 *    through `requestNavigation` — is held by `UnsavedChangesNavigationGuard`,
 *    which asks whether to keep editing, discard, or (when `onSave` is given)
 *    save and then leave.
 *
 * React Router's `useBlocker` needs the data router; this app uses
 * `<BrowserRouter>`, hence the guard component instead.
 *
 * @param isDirty Whether there are unsaved changes
 * @param options.onSave Offers "Save & leave": resolve `true` once saved
 */
export function useUnsavedChangesGuard(isDirty: boolean, options?: { onSave?: () => Promise<boolean> }) {
  const ref = useRef<UnsavedGuard>({ isDirty, onSave: options?.onSave });
  // Read at click time, so the latest render's values are the ones that count.
  useEffect(() => {
    ref.current = { isDirty, onSave: options?.onSave };
  });

  useEffect(() => registerGuard(ref), []);

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
