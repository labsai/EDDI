import { useCallback } from "react";
import { useBlocker, type BlockerFunction } from "react-router-dom";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";
import { consumeNavigationBypass, hasUnsavedChanges } from "@/lib/unsaved-changes";

/**
 * Asks before any in-app navigation would discard an unsaved edit.
 *
 * Mounted once, at the router root (`main.tsx`). Editors do not mount their own
 * blocker — React Router allows only one — they report through
 * `useUnsavedChangesGuard`, and this component consults that registry when a
 * navigation is attempted.
 *
 * Only a change of PATH is blocked. A query-string change keeps the same page,
 * and with it the edit, on screen.
 *
 * Requires a data router (`useBlocker` throws under `<BrowserRouter>` /
 * `<MemoryRouter>`), which is why it lives outside `App`: page and routing tests
 * render `App` inside a `MemoryRouter`.
 */
export function UnsavedChangesNavigationGuard() {
  const shouldBlock = useCallback<BlockerFunction>(({ currentLocation, nextLocation }) => {
    if (currentLocation.pathname === nextLocation.pathname) return false;
    if (consumeNavigationBypass()) return false;
    return hasUnsavedChanges();
  }, []);

  const blocker = useBlocker(shouldBlock);

  return (
    <UnsavedChangesDialog
      open={blocker.state === "blocked"}
      onConfirm={() => blocker.proceed?.()}
      onCancel={() => blocker.reset?.()}
    />
  );
}
