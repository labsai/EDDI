import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../hooks/use-auth";
import { createInvalidationCoalescer } from "../../lib/coalesce-invalidations";
import { appUserEventStream, REFETCH_ON_OPEN, staleQueryKeys } from "../../lib/user-event-stream";

/**
 * Opens the tab's user event stream (B12) while someone is signed in, closes it
 * on sign-out, and turns what arrives into refetches.
 *
 * Mounted once by each signed-in app's layout (the Manager's `AppLayout`,
 * Workforce's `WorkforceLayout`). Renders nothing. Every event marks the
 * queries it concerns stale (`staleQueryKeys`) so the approvals inbox, the
 * paused card, the sidebar's sessions and the notification bell show what
 * happened without waiting for a poll; a (re)connect refetches all of them,
 * because the stream has no replay and something may have happened while it
 * was down.
 */
export function UserEventBridge() {
  const { authenticated, loading } = useAuth();
  const queryClient = useQueryClient();
  const signedIn = authenticated && !loading;

  useEffect(() => {
    const stream = appUserEventStream();
    // A burst of events (a team discussion, a batch) refetches each query at
    // most once per window; one isolated event still refetches at once.
    const coalescer = createInvalidationCoalescer((queryKey) => {
      void queryClient.invalidateQueries({ queryKey: [...queryKey] });
    });
    const offEvents = stream.on("*", (event) => {
      for (const queryKey of staleQueryKeys(event)) coalescer.invalidate(queryKey);
    });
    const offStatus = stream.onStatus((status) => {
      if (status !== "open") return;
      for (const queryKey of REFETCH_ON_OPEN) coalescer.invalidate(queryKey);
    });
    return () => {
      offEvents();
      offStatus();
      coalescer.dispose();
    };
  }, [queryClient]);

  useEffect(() => {
    if (!signedIn) return;
    const stream = appUserEventStream();
    stream.start();
    return () => stream.stop();
  }, [signedIn]);

  return null;
}
