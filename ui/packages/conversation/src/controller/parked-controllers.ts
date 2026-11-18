/* ──────────────────────────────────────────────
   Leaving a page does not discard the exchange

   A page that owns a controller (the Workforce member thread) used to reset it
   on unmount. Reset aborts the fetch, and closing the stream cancels the turn on
   the server, which then stores nothing: a user who clicked away while the agent
   was answering lost the whole exchange.

   `ParkedControllers` keeps a controller whose turn is still running for a grace
   period after its page left. Coming back within it (the page asks for the same
   key) hands the running controller back, so the turn is on screen again — and
   the turn finishes on the server either way. When the grace runs out the turn is
   aborted, as it always was, so a proxy that swallows the terminal frame cannot
   keep a stream open for good. An idle controller is reset at once.

   The Manager's chat is one controller for the whole app and uses the controller's
   own `detachedGraceMs`; this is the same behaviour for pages that create one
   controller per visit.
   ────────────────────────────────────────────── */

import type { AgentConversation } from "./agent-conversation";

/** How long a turn is left to finish after its page was left, before it is aborted. */
export const DETACHED_TURN_GRACE_MS = 120_000;

export interface ParkedEntry {
  controller: AgentConversation;
}

interface Slot<E extends ParkedEntry> {
  entry: E;
  /** Set while parked: the timer that aborts the turn when the grace runs out. */
  timer: ReturnType<typeof setTimeout> | null;
}

export class ParkedControllers<E extends ParkedEntry> {
  private readonly slots = new Map<string, Slot<E>>();

  constructor(private readonly graceMs: number = DETACHED_TURN_GRACE_MS) {}

  /**
   * The entry for `key`: the one a turn kept running in while its page was away
   * (`resumed`), the one already in use, or a new one from `create`.
   */
  acquire(key: string, create: () => E): { entry: E; resumed: boolean } {
    const slot = this.slots.get(key);
    if (slot) {
      const resumed = slot.timer !== null;
      if (slot.timer !== null) {
        clearTimeout(slot.timer);
        slot.timer = null;
      }
      return { entry: slot.entry, resumed };
    }
    const entry = create();
    this.slots.set(key, { entry, timer: null });
    return { entry, resumed: false };
  }

  /**
   * The page left. A running turn parks the controller for the grace period; an
   * idle one is reset and forgotten.
   */
  release(key: string): void {
    const slot = this.slots.get(key);
    if (!slot || slot.timer !== null) return;
    const { controller } = slot.entry;
    if (!controller.getSnapshot().isProcessing) {
      this.slots.delete(key);
      controller.reset();
      return;
    }
    slot.timer = setTimeout(() => {
      // Only this slot: a page that came back has replaced the timer with null.
      if (this.slots.get(key) === slot) this.slots.delete(key);
      controller.reset();
    }, this.graceMs);
  }

  /** Whether a controller is parked for `key` (tests, and the "turn still running" hint). */
  isParked(key: string): boolean {
    return this.slots.get(key)?.timer != null;
  }
}
