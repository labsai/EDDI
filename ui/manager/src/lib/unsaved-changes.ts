/**
 * The app-wide record of "something on screen has unsaved edits".
 *
 * ## Why a registry rather than a per-page blocker
 *
 * React Router's `useBlocker` allows ONE active blocker at a time, and a page
 * that is dirty is not the only thing that decides whether leaving is safe: the
 * Agent Studio renders a config editor inside a page, the Workforce editor sheet
 * sits on top of a board, and either can be the one holding the edit. So every
 * editor reports into this registry (through `useUnsavedChangesGuard`), and a
 * single `UnsavedChangesNavigationGuard` mounted at the router root asks it
 * before any in-app navigation — sidebar, breadcrumb, command palette, Back.
 *
 * A plain module rather than a store: nothing renders from it. It is read at the
 * moment a navigation is attempted, never subscribed to.
 */

const dirtySources = new Set<string>();

/**
 * One-shot permission for the next cross-page navigation.
 *
 * For the handful of places that navigate away *on purpose* while the page
 * still reports dirty: a delete that has just removed the document being
 * edited, or a page's own "Discard & leave" prompt that already asked. Without
 * it the user would be asked twice, or asked to protect a document that no
 * longer exists.
 */
let bypassNext = false;

/** Record whether the source `id` currently holds unsaved edits. */
export function setUnsavedChanges(id: string, dirty: boolean): void {
  if (dirty) dirtySources.add(id);
  else dirtySources.delete(id);
}

/** Whether any mounted editor reports unsaved edits. */
export function hasUnsavedChanges(): boolean {
  return dirtySources.size > 0;
}

/**
 * Let the next cross-page navigation through without asking.
 *
 * Call it immediately before a `navigate()` that is meant to discard — it is
 * consumed by the very next navigation the guard sees.
 */
export function allowNextNavigation(): void {
  bypassNext = true;
}

/** Read and clear the one-shot bypass. Used by the navigation guard only. */
export function consumeNavigationBypass(): boolean {
  const bypass = bypassNext;
  bypassNext = false;
  return bypass;
}
