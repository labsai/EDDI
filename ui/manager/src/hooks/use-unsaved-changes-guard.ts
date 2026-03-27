import { useEffect, useCallback } from "react";
import { useBlocker } from "react-router-dom";

/**
 * Prevent navigation away from the page when there are unsaved changes.
 * Uses both the browser's `beforeunload` event (for tab close/reload)
 * and React Router's `useBlocker` (for in-app navigation).
 *
 * @param isDirty Whether there are unsaved changes
 * @param message Custom confirmation message (optional)
 */
export function useUnsavedChangesGuard(
  isDirty: boolean,
  message = "You have unsaved changes. Are you sure you want to leave?"
) {
  // Browser close/reload protection
  useEffect(() => {
    if (!isDirty) return;

    function handleBeforeUnload(e: BeforeUnloadEvent) {
      e.preventDefault();
      // Modern browsers ignore custom messages but still show prompt
      e.returnValue = message;
    }

    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [isDirty, message]);

  // React Router navigation blocker
  const blocker = useBlocker(
    useCallback(
      ({ currentLocation, nextLocation }: { currentLocation: { pathname: string }; nextLocation: { pathname: string } }) => {
        return isDirty && currentLocation.pathname !== nextLocation.pathname;
      },
      [isDirty]
    )
  );

  return blocker;
}
