import { useEffect, useId } from "react";
import { setUnsavedChanges } from "@/lib/unsaved-changes";

/**
 * Prevent accidental data loss when there are unsaved changes.
 *
 * Two exits are covered:
 *  - **Leaving the app** (tab close, reload, typed URL): the browser's own
 *    `beforeunload` prompt.
 *  - **In-app navigation** (sidebar, breadcrumb, command palette, Back): this
 *    hook registers the edit in `@/lib/unsaved-changes`, and the single
 *    `UnsavedChangesNavigationGuard` at the router root blocks the navigation
 *    and asks. That guard needs the data router (`createBrowserRouter` in
 *    `main.tsx`); before the switch this hook covered `beforeunload` only, and
 *    every in-app exit silently discarded the edit.
 *
 * Navigations that only change the query string (a version switch, a tab) are
 * not blocked — the page is still on screen with the edit.
 *
 * @param isDirty Whether there are unsaved changes
 */
export function useUnsavedChangesGuard(isDirty: boolean) {
  const id = useId();

  useEffect(() => {
    setUnsavedChanges(id, isDirty);
    return () => setUnsavedChanges(id, false);
  }, [id, isDirty]);

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
