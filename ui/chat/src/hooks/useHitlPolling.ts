/* ──────────────────────────────────────────────
   useHitlPolling — watch a paused conversation until it settles

   The 1:1 REST/SSE surface has no push channel for HITL: once a turn pauses,
   nothing tells the client that a reviewer decided, or that the timeout policy
   auto-decided. Polling approval-status is the only way to find out.
   ────────────────────────────────────────────── */

import { useEffect, useRef } from "react";
import {
  UNOBSERVED_PAUSE_WINDOW_MS,
  getApprovalStatus,
  isSettledState,
  nextPollDelay,
  nextUnobservedPollDelay,
} from "@/api/hitl-api";
import type { ApprovalStatus } from "@/api/hitl-api";

interface UseHitlPollingArgs {
  conversationId: string | null;
  /** Poll only while this is true. */
  paused: boolean;
  onStatus: (status: ApprovalStatus | null) => void;
  /**
   * Called when the conversation reaches a settled state. Returns true if the
   * caller successfully picked up the resumed turn; false means "try again" and
   * the watch keeps running.
   *
   * `isStale()` reports whether the watch was torn down while the refresh was
   * in flight — the caller MUST consult it before applying a snapshot, or a
   * restarted conversation adopts the old one's transcript.
   */
  onResolved: (isStale: () => boolean) => Promise<boolean>;
}

function isHidden(): boolean {
  return typeof document !== "undefined" && document.visibilityState === "hidden";
}

export function useHitlPolling({
  conversationId,
  paused,
  onStatus,
  onResolved,
}: UseHitlPollingArgs): void {
  // Keep the callbacks in refs so a new inline function on each render does
  // not restart the polling loop.
  const onStatusRef = useRef(onStatus);
  const onResolvedRef = useRef(onResolved);
  onStatusRef.current = onStatus;
  onResolvedRef.current = onResolved;

  useEffect(() => {
    if (!paused || !conversationId) return;

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let attempt = 0;
    // The stream announces the pause BEFORE the paused memory is stored, and
    // approval-status reads the store. A READY read taken before the pause has
    // ever been seen stored is therefore "not stored yet", not "resolved":
    // acting on it refreshed to the OLD snapshot and dropped the paused card.
    // Once the pause HAS been seen (AWAITING_HUMAN, or IN_PROGRESS while the
    // approved turn runs), any settled state is a genuine resolution. If it is
    // never seen, the window below ends the doubt: a pause that was decided
    // faster than the first poll looks exactly like this, and ENDED / ERROR /
    // EXECUTION_INTERRUPTED are never what a not-yet-stored read returns.
    let observedPause = false;
    const startedAt = Date.now();
    // A hidden tab does not poll: the next read is parked until the tab is
    // visible again, then taken at once — the reviewer may well have decided
    // while the user was away, and that is the moment they look.
    let inFlight = false;
    let parked = false;

    const schedule = () => {
      if (cancelled) return;
      if (isHidden()) {
        parked = true;
        return;
      }
      timer = setTimeout(
        poll,
        observedPause || Date.now() - startedAt >= UNOBSERVED_PAUSE_WINDOW_MS
          ? nextPollDelay(attempt)
          : nextUnobservedPollDelay(attempt),
      );
      attempt += 1;
    };

    const onVisibilityChange = () => {
      if (cancelled || isHidden() || inFlight) return;
      if (!parked && timer === undefined) return;
      parked = false;
      if (timer) clearTimeout(timer);
      timer = undefined;
      void poll();
    };

    const poll = async () => {
      timer = undefined;
      inFlight = true;
      try {
        const status = await getApprovalStatus(conversationId);
        if (cancelled) return;

        // IN_PROGRESS is NOT resolution: resume() flips AWAITING_HUMAN ->
        // IN_PROGRESS before running the approved turn, so treating it as
        // resolved abandons the watch while the answer is still being produced.
        if (status?.state === "AWAITING_HUMAN" || status?.state === "IN_PROGRESS") {
          observedPause = true;
        }
        const doubtful =
          status?.state === "READY" &&
          !observedPause &&
          Date.now() - startedAt < UNOBSERVED_PAUSE_WINDOW_MS;
        if (isSettledState(status?.state) && !doubtful) {
          const handled = await onResolvedRef.current(() => cancelled);
          if (cancelled) return;
          if (handled) {
            onStatusRef.current(null);
            return;
          }
          // The refresh failed. Keep watching rather than stranding the widget
          // in a paused state it can never leave.
        } else if (status?.state === "AWAITING_HUMAN") {
          onStatusRef.current(status);
        }
        // A mid-resume IN_PROGRESS is deliberately NOT reported: clearing the
        // status here blanked the paused card — and its Cancel button — for the
        // whole of the resumed turn, leaving an empty screen and a locked
        // composer. Keeping the last pause context on screen is the honest
        // rendering: the turn genuinely has not finished.
      } catch {
        // Transient failures must not end the watch; the pause outlives them.
        // Backing off below keeps a persistent outage from spinning.
      } finally {
        inFlight = false;
      }

      schedule();
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    // First read immediately: the pause is already in effect.
    void poll();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [conversationId, paused]);
}
