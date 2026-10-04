import { useContext, useEffect, useRef } from "react";
import { UNSAFE_DataRouterContext, useBlocker } from "react-router-dom";
import { create } from "zustand";

/**
 * A navigation the guard has held back, waiting for the user's answer.
 * Rendered by `NavigationGuardDialog`, mounted once at the app root.
 */
interface PendingNavigation {
  /** Let the held navigation through — the user chose to discard. */
  proceed: () => void;
  /** Cancel the held navigation — the user chose to stay. */
  reset: () => void;
}

interface NavigationGuardState {
  pending: PendingNavigation | null;
}

export const useNavigationGuardStore = create<NavigationGuardState>(() => ({
  pending: null,
}));

/** One-shot escape hatch state, see {@link allowNextNavigation}. */
let bypassNext = false;

/**
 * Let the next in-app navigation through even though the page is dirty.
 *
 * For a programmatic `navigate()` whose edits are already resolved but whose
 * `isDirty` has not re-rendered yet — navigating away right after a delete, or
 * after the user has already confirmed a discard in a dialog of the page's own.
 * Call it immediately before `navigate(...)`; it covers that one navigation only.
 */
export function allowNextNavigation(): void {
  bypassNext = true;
  // A navigation is evaluated synchronously, so anything still set after the
  // current task was not consumed and must not leak onto an unrelated one.
  setTimeout(() => {
    bypassNext = false;
  }, 0);
}

/**
 * Prevent accidental data loss when there are unsaved changes.
 *
 * Two layers:
 *  - `beforeunload` covers closing the tab, reloading, and leaving for another
 *    origin — the browser shows its own native prompt.
 *  - A router blocker (`useBlocker`) covers in-app navigation: a sidebar link,
 *    the breadcrumb, the command palette, the browser's Back button. The held
 *    navigation is parked in a store and `NavigationGuardDialog` asks Stay or
 *    Discard. Only a change of PATH is held — switching a tab through a query
 *    parameter on the same page loses nothing.
 *
 * `useBlocker` exists only under a data router (`RouterProvider`). The app runs
 * under one; isolated component tests that mount a bare `MemoryRouter` do not, and
 * for them the blocker layer is simply absent rather than a thrown error.
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

  // Whether a data router is present never changes over a component's lifetime,
  // so calling the blocker hook conditionally keeps the hook order stable.
  const hasDataRouter = useContext(UNSAFE_DataRouterContext) != null;
  if (hasDataRouter) {
    // eslint-disable-next-line react-hooks/rules-of-hooks
    useRouteBlocker(isDirty);
  }
}

function useRouteBlocker(isDirty: boolean) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (bypassNext) {
      bypassNext = false;
      return false;
    }
    return isDirty && currentLocation.pathname !== nextLocation.pathname;
  });

  // Hold the latest blocker in a ref so the unmount cleanup releases exactly the
  // request this instance published and never one belonging to a later page.
  const published = useRef<PendingNavigation | null>(null);

  useEffect(() => {
    if (blocker.state === "blocked") {
      const request: PendingNavigation = {
        proceed: () => blocker.proceed(),
        reset: () => blocker.reset(),
      };
      published.current = request;
      useNavigationGuardStore.setState({ pending: request });
    } else if (published.current) {
      if (useNavigationGuardStore.getState().pending === published.current) {
        useNavigationGuardStore.setState({ pending: null });
      }
      published.current = null;
    }
  }, [blocker]);

  useEffect(
    () => () => {
      if (published.current && useNavigationGuardStore.getState().pending === published.current) {
        useNavigationGuardStore.setState({ pending: null });
      }
    },
    [],
  );
}
