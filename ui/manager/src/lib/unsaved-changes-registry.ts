/**
 * The pages and sheets that currently hold unsaved edits, and the leave request
 * waiting for the user's answer.
 *
 * `useUnsavedChangesGuard` registers here; `UnsavedChangesNavigationGuard`
 * (mounted once, inside the router) reads it to decide whether a link click or
 * a back/forward step may go ahead, and shows the Keep editing / Discard / Save
 * prompt when it may not. Module state rather than React context, because the
 * browser's `popstate` listener has to be installed before React Router's own —
 * see `installPopstateGuard`.
 */

export interface UnsavedGuard {
  isDirty: boolean;
  /**
   * Saves the edits. Resolves `true` when the save landed and leaving may
   * continue; `false` (or a rejection) keeps the user on the page — the save
   * reports its own error.
   */
  onSave?: () => Promise<boolean>;
}

/** What will happen once the user lets the navigation through. */
export type PendingLeave = { kind: "push"; proceed: () => void } | { kind: "pop"; delta: number };

const guards = new Set<{ current: UnsavedGuard }>();
const listeners = new Set<() => void>();
let pending: PendingLeave | null = null;

function emit() {
  listeners.forEach((l) => l());
}

export function registerGuard(ref: { current: UnsavedGuard }): () => void {
  guards.add(ref);
  return () => {
    guards.delete(ref);
  };
}

/** The first registered guard with unsaved edits, if any. */
export function activeGuard(): UnsavedGuard | null {
  for (const ref of guards) if (ref.current.isDirty) return ref.current;
  return null;
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getPendingLeave(): PendingLeave | null {
  return pending;
}

export function setPendingLeave(next: PendingLeave | null) {
  pending = next;
  emit();
}

/**
 * Navigate with `go`, unless something holds unsaved edits — then ask first.
 * For programmatic navigation (the command palette, buttons that `navigate()`)
 * that the link-click interception cannot see.
 */
export function requestNavigation(go: () => void) {
  if (!activeGuard()) {
    go();
    return;
  }
  setPendingLeave({ kind: "push", proceed: go });
}

// ─── Back / forward ───────────────────────────────────────────────────────────

let installed = false;
let currentIdx: number | null = null;
/** Set while undoing a step we blocked, so its popstate is swallowed. */
let swallowNext = false;
/** Set while replaying a step the user approved, so it passes through. */
let letNextThrough = false;

function historyIdx(state: unknown): number | null {
  const idx = (state as { idx?: unknown } | null)?.idx;
  return typeof idx === "number" ? idx : null;
}

/** Called by the navigation guard whenever the router's location changes. */
export function noteHistoryIndex() {
  currentIdx = historyIdx(window.history.state);
}

/**
 * Hold back/forward steps that would leave unsaved edits.
 *
 * The browser has already moved by the time `popstate` fires, so a blocked step
 * is undone (`history.go(-delta)`) and replayed (`history.go(delta)`) if the
 * user chooses to leave. The listener must run before React Router's — which is
 * why it is installed at module load, before the router mounts, and stops the
 * event from reaching the router while the step is held. The step size comes
 * from the `idx` React Router keeps in `history.state`; a step without one
 * (an entry from before the app loaded) is let through, since it cannot be put
 * back.
 */
export function installPopstateGuard() {
  if (installed || typeof window === "undefined") return;
  installed = true;
  currentIdx = historyIdx(window.history.state);
  window.addEventListener("popstate", (event) => {
    if (swallowNext) {
      swallowNext = false;
      event.stopImmediatePropagation();
      return;
    }
    if (letNextThrough) {
      letNextThrough = false;
      return;
    }
    if (!activeGuard()) return;
    const target = historyIdx(event.state);
    if (target === null || currentIdx === null || target === currentIdx) return;
    const delta = target - currentIdx;
    event.stopImmediatePropagation();
    swallowNext = true;
    window.history.go(-delta);
    setPendingLeave({ kind: "pop", delta });
  });
}

/** Carry out the held step once the user has chosen to leave. */
export function proceedWith(leave: PendingLeave) {
  if (leave.kind === "push") {
    leave.proceed();
  } else {
    letNextThrough = true;
    window.history.go(leave.delta);
  }
}
