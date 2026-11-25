import { useEffect, useRef, useSyncExternalStore } from "react";
import type { UserEvent, UserEventKind, UserEventStreamStatus } from "@eddi/core/user-events";
import { appUserEventStream } from "../lib/user-event-stream";

/**
 * Calls `handler` for every event of `kind` (or `"*"`) on the tab's stream
 * while the component is mounted. It only subscribes: `UserEventBridge` opens
 * and closes the stream. The latest `handler` is called, so it need not be
 * memoised.
 */
export function useUserEvent(kind: UserEventKind | "*", handler: (event: UserEvent) => void): void {
  const ref = useRef(handler);
  useEffect(() => {
    ref.current = handler;
  });
  useEffect(() => appUserEventStream().on(kind, (event) => ref.current(event)), [kind]);
}

function subscribeStatus(onChange: () => void): () => void {
  return appUserEventStream().onStatus(onChange);
}

function readStatus(): UserEventStreamStatus {
  return appUserEventStream().status;
}

/**
 * The stream's status. `open` means events arrive, so a list kept fresh by
 * them need not poll; anything else (connecting, waiting to reconnect, refused
 * because the deployment switched the stream off) means it should.
 */
export function useUserEventStatus(): UserEventStreamStatus {
  return useSyncExternalStore(subscribeStatus, readStatus, readStatus);
}

/**
 * The poll interval for a list the stream keeps fresh: none while the stream is
 * open, `fallbackMs` otherwise.
 */
export function useEventDrivenInterval(fallbackMs: number): number | false {
  return useUserEventStatus() === "open" ? false : fallbackMs;
}
