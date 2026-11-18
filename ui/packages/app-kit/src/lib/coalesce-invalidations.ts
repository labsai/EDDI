/**
 * Coalesces query invalidations per query key.
 *
 * The first invalidation of a key runs at once (leading edge), so a single
 * isolated event shows with no added delay. Further requests for the same key
 * within `windowMs` collapse into ONE trailing invalidation at the end of the
 * window, so a burst costs at most one refetch per window per key and a
 * continuous stream still refreshes every `windowMs` (the window is also the
 * maximum wait). TanStack's `invalidateQueries` cancels and restarts an
 * in-flight fetch by default, so an uncoalesced burst would restart the same
 * request over and over.
 */
export const INVALIDATION_WINDOW_MS = 500;

export interface InvalidationCoalescer {
  /** Invalidate `queryKey` now, or once at the end of the current window. */
  invalidate(queryKey: readonly unknown[]): void;
  /** Cancels every pending trailing invalidation. */
  dispose(): void;
}

interface Slot {
  timer: ReturnType<typeof setTimeout>;
  pending: boolean;
}

export function createInvalidationCoalescer(
  run: (queryKey: readonly unknown[]) => void,
  windowMs: number = INVALIDATION_WINDOW_MS,
): InvalidationCoalescer {
  const slots = new Map<string, Slot>();

  const open = (id: string, queryKey: readonly unknown[]): void => {
    const slot: Slot = {
      pending: false,
      timer: setTimeout(() => {
        if (slots.get(id) !== slot) return;
        slots.delete(id);
        if (slot.pending) {
          // The trailing run starts a new window, so a continuous stream keeps
          // refreshing once per window rather than once per event.
          run(queryKey);
          open(id, queryKey);
        }
      }, windowMs),
    };
    slots.set(id, slot);
  };

  return {
    invalidate(queryKey) {
      const id = JSON.stringify(queryKey);
      const slot = slots.get(id);
      if (slot) {
        slot.pending = true;
        return;
      }
      run(queryKey);
      open(id, queryKey);
    },
    dispose() {
      for (const slot of slots.values()) clearTimeout(slot.timer);
      slots.clear();
    },
  };
}
