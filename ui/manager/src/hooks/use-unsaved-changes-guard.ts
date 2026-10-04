import { useEffect, useId } from "react";
import { create } from "zustand";

/**
 * A navigation held back, waiting for the user's answer. Rendered by
 * `NavigationGuardDialog`, mounted once at the app root.
 */
export interface PendingNavigation {
  /** Let the held navigation through — the user chose to discard. */
  proceed: () => void;
  /** Cancel the held navigation — the user chose to stay. */
  reset: () => void;
}

interface NavigationGuardState {
  pending: PendingNavigation | null;
  /** Tokens of the mounted guards that currently have unsaved changes. */
  dirtyGuards: ReadonlySet<string>;
}

export const useNavigationGuardStore = create<NavigationGuardState>(() => ({
  pending: null,
  dirtyGuards: new Set<string>(),
}));

function setGuardDirty(token: string, dirty: boolean) {
  useNavigationGuardStore.setState((s) => {
    if (s.dirtyGuards.has(token) === dirty) return s;
    const next = new Set(s.dirtyGuards);
    if (dirty) next.add(token);
    else next.delete(token);
    return { dirtyGuards: next };
  });
}

/** Whether any mounted guard has unsaved changes. Read by the single app blocker. */
export function anyGuardDirty(): boolean {
  return useNavigationGuardStore.getState().dirtyGuards.size > 0;
}

/** One-shot escape hatch state, see {@link allowNextNavigation}. */
let bypassNext = false;

/**
 * Let the next in-app navigation through even though a page is dirty.
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

/** Consume the bypass flag; true when this navigation was allowed through. */
export function consumeNavigationBypass(): boolean {
  if (!bypassNext) return false;
  bypassNext = false;
  return true;
}

/**
 * Prevent accidental data loss when there are unsaved changes.
 *
 * Two layers:
 *  - `beforeunload` covers closing the tab, reloading, and leaving for another
 *    origin — the browser shows its own native prompt.
 *  - In-app navigation (a sidebar link, the breadcrumb, the command palette, the
 *    Back button) is held by ONE router blocker, mounted by
 *    `NavigationGuardDialog`. This hook only registers its dirty flag in a store.
 *    It must not call `useBlocker` itself: a router supports a single blocker and
 *    consults only the last one registered, so a clean guard mounted after a
 *    dirty one would silently take over and leave the dirty page unprotected.
 *
 * Only a change of PATH is held — switching a tab through a query parameter on the
 * same page loses nothing. Under a bare `MemoryRouter` (isolated component tests)
 * there is no blocker, and only `beforeunload` applies.
 *
 * @param isDirty Whether there are unsaved changes
 */
export function useUnsavedChangesGuard(isDirty: boolean) {
  const token = useId();

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

  useEffect(() => {
    if (!isDirty) return;
    setGuardDirty(token, true);
    return () => setGuardDirty(token, false);
  }, [isDirty, token]);
}
