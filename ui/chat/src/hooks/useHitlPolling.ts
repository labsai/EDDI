/* ──────────────────────────────────────────────
   useHitlPolling — watch a paused conversation until it settles

   The 1:1 REST/SSE surface has no push channel for HITL: once a turn pauses,
   nothing tells the client that a reviewer decided, or that the timeout policy
   auto-decided. Polling approval-status is the only way to find out.
   ────────────────────────────────────────────── */

import { useEffect, useRef } from "react";
import { getApprovalStatus, isSettledState, nextPollDelay } from "@/api/hitl-api";
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
   */
  onResolved: () => Promise<boolean>;
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

    const poll = async () => {
      try {
        const status = await getApprovalStatus(conversationId);
        if (cancelled) return;

        // IN_PROGRESS is NOT resolution: resume() flips AWAITING_HUMAN ->
        // IN_PROGRESS before running the approved turn, so treating it as
        // resolved abandons the watch while the answer is still being produced.
        if (isSettledState(status?.state)) {
          const handled = await onResolvedRef.current();
          if (cancelled) return;
          if (handled) {
            onStatusRef.current(null);
            return;
          }
          // The refresh failed. Keep watching rather than stranding the widget
          // in a paused state it can never leave.
        } else {
          // Only surface a status that still describes a pause; a mid-resume
          // IN_PROGRESS would otherwise blank the card for the whole turn.
          onStatusRef.current(status?.state === "AWAITING_HUMAN" ? status : null);
        }
      } catch {
        // Transient failures must not end the watch; the pause outlives them.
        // Backing off below keeps a persistent outage from spinning.
      }

      if (cancelled) return;
      timer = setTimeout(poll, nextPollDelay(attempt));
      attempt += 1;
    };

    // First read immediately: the pause is already in effect.
    void poll();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [conversationId, paused]);
}
