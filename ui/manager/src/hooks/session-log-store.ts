import { create } from "zustand";
import { createLogEventSource, type LogEntry, getRecentLogs } from "@/lib/api/logs";
import type { BearerEventSource } from "@/lib/bearer-event-source";

/**
 * Session-level log store — a buffer of unfiltered log entries, shared by every
 * consumer that wants a live tail.
 *
 * **The stream is lazy and reference-counted.** This module used to connect on
 * import, and `main.tsx` imported it for that side effect, so every Manager tab
 * held an open `/administration/logs/stream` SSE connection for its whole
 * lifetime — on every page, whether or not anyone ever opened the Logs page.
 * EDDI serves HTTP/1.1, where Chrome allows **six** concurrent connections per
 * origin across the entire profile, and a live group discussion opens another.
 * Two or three Manager tabs saturated the cap: pages hung on skeleton loaders
 * forever, intermittently, while the server was provably fine. It looks exactly
 * like a dead backend and is not.
 *
 * What was lost by making it lazy is small: the buffer no longer accumulates
 * from app boot. It never needed to — {@link connect} seeds from
 * `getRecentLogs` on open, so arriving at the Logs page still shows history.
 *
 * Usage: call {@link connect} on mount and the returned release (or
 * {@link disconnect}) on unmount. The second caller reuses the open stream; the
 * socket closes when the last one leaves.
 */

const MAX_SESSION_ENTRIES = 1000;

interface SessionLogState {
  entries: LogEntry[];
  connected: boolean;
  seeded: boolean;
}

export const useSessionLogStore = create<SessionLogState>(() => ({
  entries: [],
  connected: false,
  seeded: false,
}));

// ─── Lazy, reference-counted SSE ─────────────────────────────────────────────

let eventSource: BearerEventSource | null = null;
let refCount = 0;

function openStream() {
  try {
    eventSource = createLogEventSource(); // no filters — capture everything

    const handleEvent = (event: MessageEvent) => {
      try {
        const entry = JSON.parse(event.data) as LogEntry;
        useSessionLogStore.setState((s) => {
          const next = [entry, ...s.entries];
          return {
            entries:
              next.length > MAX_SESSION_ENTRIES
                ? next.slice(0, MAX_SESSION_ENTRIES)
                : next,
          };
        });
      } catch {
        // ignore parse errors
      }
    };

    // Listen for both named "log" events and unnamed events (fallback)
    eventSource.addEventListener("log", handleEvent);
    eventSource.onmessage = handleEvent;

    // Reconnection is BearerEventSource's job, on the bounded policy in
    // `sse-reconnect.ts`. This used to close the source and re-create it on an
    // unbounded `setTimeout(connect, 5000)`, which meant a stream the backend
    // will never serve — `/administration/logs` answers 403 without the
    // `eddi-admin` role — was re-requested every five seconds for the entire
    // session.
    eventSource.onerror = () => {
      useSessionLogStore.setState({ connected: false });
    };

    eventSource.onexhausted = () => {
      useSessionLogStore.setState({ connected: false, seeded: true });
    };

    eventSource.onopen = async () => {
      useSessionLogStore.setState({ connected: true });

      if (useSessionLogStore.getState().entries.length === 0) {
        try {
          const recentLogs = await getRecentLogs({ limit: 200 });
          useSessionLogStore.setState((s) => {
            const merged = [...s.entries, ...recentLogs];
            const seen = new Set<string>();
            const unique: LogEntry[] = [];

            for (const entry of merged) {
              const key = `${entry.timestamp}-${entry.message}`;
              if (!seen.has(key)) {
                seen.add(key);
                unique.push(entry);
              }
            }

            unique.sort((a, b) => b.timestamp - a.timestamp);

            return {
              entries:
                unique.length > MAX_SESSION_ENTRIES
                  ? unique.slice(0, MAX_SESSION_ENTRIES)
                  : unique,
              seeded: true,
            };
          });
        } catch {
          useSessionLogStore.setState({ seeded: true });
        }
      } else {
        // Entries already exist from SSE — mark seeded so UI doesn't show loading
        useSessionLogStore.setState({ seeded: true });
      }
    };
  } catch {
    useSessionLogStore.setState({ connected: false });
  }
}

function closeStream() {
  eventSource?.close();
  eventSource = null;
  useSessionLogStore.setState({ connected: false });
}

/**
 * Subscribe to the unfiltered log stream, opening it if nobody else has.
 *
 * @returns a release function — calling it is exactly {@link disconnect}, and
 *          calling it twice releases only once, so it is safe as a `useEffect`
 *          cleanup under React 19's double-invoked effects.
 */
export function connect(): () => void {
  refCount += 1;
  if (refCount === 1) {
    openStream();
  }
  let released = false;
  return () => {
    if (released) return;
    released = true;
    disconnect();
  };
}

/** Release one subscription; closes the stream when the last one leaves. */
export function disconnect(): void {
  if (refCount === 0) return;
  refCount -= 1;
  if (refCount === 0) {
    closeStream();
  }
}

/** Open subscriptions. Exported for tests and diagnostics. */
export function subscriberCount(): number {
  return refCount;
}

/**
 * Whether a socket is currently held. Exported so a test can assert the thing
 * that actually regressed — that merely importing this module opens nothing.
 */
export function isStreamOpen(): boolean {
  return eventSource !== null;
}

export function _connectForTesting() {
  const release = connect();
  return {
    close: () => release(),
    getEventSource: () => eventSource,
  };
}
