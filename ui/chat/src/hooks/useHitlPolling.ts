/* ──────────────────────────────────────────────
   useHitlPolling — watch a paused conversation until it resolves

   The 1:1 REST/SSE surface has no push channel for HITL: once a turn pauses,
   nothing tells the client that a reviewer decided, or that the timeout policy
   auto-decided. Polling approval-status is the only way to find out.
   ────────────────────────────────────────────── */

import { useEffect, useRef } from "react";
import { getApprovalStatus, nextPollDelay } from "@/api/hitl-api";
import type { ApprovalStatus } from "@/api/hitl-api";

interface UseHitlPollingArgs {
  conversationId: string | null;
  /** Poll only while this is true. */
  paused: boolean;
  onStatus: (status: ApprovalStatus | null) => void;
  /** Called once when the conversation leaves AWAITING_HUMAN. */
  onResolved: () => void;
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

        if (status && status.state !== "AWAITING_HUMAN") {
          // Resolved — by a reviewer, or automatically by timeout policy.
          onStatusRef.current(null);
          onResolvedRef.current();
          return;
        }
        onStatusRef.current(status);
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
