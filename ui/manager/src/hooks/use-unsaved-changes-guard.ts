import { useCallback, useContext, useEffect, useRef } from "react";
import { UNSAFE_DataRouterContext, useBlocker, type BlockerFunction } from "react-router-dom";

/**
 * What a page needs to render the in-app "leave without saving?" prompt.
 *
 * Render it with `<UnsavedChangesPrompt guard={…} />`.
 */
export interface UnsavedChangesGuard {
  /** True while an in-app navigation is held, waiting for the user's answer. */
  blocked: boolean;
  /** Leave anyway — the held navigation continues and the edits are dropped. */
  proceed: () => void;
  /** Stay on the page; the held navigation is cancelled. */
  stay: () => void;
  /**
   * Let the NEXT navigation through without asking — for a navigation the page
   * makes on purpose while still dirty (after a delete, or after its own
   * "discard and leave" confirmation).
   */
  allowNextNavigation: () => void;
}

const INERT: UnsavedChangesGuard = {
  blocked: false,
  proceed: () => {},
  stay: () => {},
  allowNextNavigation: () => {},
};

/**
 * Prevent accidental data loss when there are unsaved changes.
 *
 * Two layers:
 *
 * 1. **Leaving the app** (tab close, reload, an external URL) — the browser's
 *    `beforeunload` prompt.
 * 2. **In-app navigation** — a sidebar link, a breadcrumb, a `navigate()` call or
 *    the browser's Back button. React Router's `useBlocker` holds the navigation
 *    and the page shows `UnsavedChangesPrompt`. This used to be missing: the app
 *    ran on `<BrowserRouter>`, which cannot block, so only (1) existed and every
 *    in-app link silently dropped the edits. `main.tsx` now mounts a data router.
 *
 * Only a change of PATH is blocked. A page that moves itself to the version its
 * save created (`?version=`) or switches a tab in the query string is still on
 * the same screen with the same edits.
 *
 * Outside a data router (unit tests rendering a page in a `MemoryRouter`) layer
 * 2 is skipped and the guard is inert — `useBlocker` throws there.
 *
 * @param isDirty Whether there are unsaved changes
 */
export function useUnsavedChangesGuard(isDirty: boolean): UnsavedChangesGuard {
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

  // Whether a data router is mounted is fixed for the life of the tree — a page
  // never moves between a data router and a plain one — so the hook order below
  // is stable for any given mount even though it is chosen at runtime.
  const inDataRouter = useContext(UNSAFE_DataRouterContext) !== null;
  // eslint-disable-next-line react-hooks/rules-of-hooks -- see the comment above
  return inDataRouter ? useNavigationBlocker(isDirty) : INERT;
}

function useNavigationBlocker(isDirty: boolean): UnsavedChangesGuard {
  const bypass = useRef(false);
  const shouldBlock = useCallback<BlockerFunction>(
    ({ currentLocation, nextLocation }) => {
      if (bypass.current) {
        bypass.current = false;
        return false;
      }
      return isDirty && currentLocation.pathname !== nextLocation.pathname;
    },
    [isDirty],
  );
  const blocker = useBlocker(shouldBlock);

  // A save that cleans the page while a navigation is held lets it through.
  useEffect(() => {
    if (blocker.state === "blocked" && !isDirty) blocker.proceed();
  }, [blocker, isDirty]);

  const proceed = useCallback(() => {
    if (blocker.state === "blocked") blocker.proceed();
  }, [blocker]);
  const stay = useCallback(() => {
    if (blocker.state === "blocked") blocker.reset();
  }, [blocker]);
  const allowNextNavigation = useCallback(() => {
    bypass.current = true;
  }, []);

  return { blocked: blocker.state === "blocked", proceed, stay, allowNextNavigation };
}
