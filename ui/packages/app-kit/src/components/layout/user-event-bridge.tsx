import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "../../hooks/use-auth";
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
    const offEvents = stream.on("*", (event) => {
      for (const queryKey of staleQueryKeys(event)) {
        void queryClient.invalidateQueries({ queryKey });
      }
    });
    const offStatus = stream.onStatus((status) => {
      if (status !== "open") return;
      for (const queryKey of REFETCH_ON_OPEN) {
        void queryClient.invalidateQueries({ queryKey });
      }
    });
    return () => {
      offEvents();
      offStatus();
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
