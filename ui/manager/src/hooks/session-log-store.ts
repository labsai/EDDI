import { create } from "zustand";
import { createLogEventSource, type LogEntry, getRecentLogs } from "@/lib/api/logs";
import type { BearerEventSource } from "@/lib/bearer-event-source";

/**
 * Session-level log store.
 * Connects to the log SSE stream on first import and buffers entries
 * so that the Logs page has data from the start of the Manager session,
 * not just from when the user first navigates there.
 *
 * Usage: import this file as a side-effect in main.tsx:
 *   import '@/hooks/session-log-store';
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

// ─── Auto-connect SSE on module load ─────────────────────────────────────────

let eventSource: BearerEventSource | null = null;

function connect() {
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
    // session. This module connects at app boot, so that happened whether or not
    // anyone ever opened the Logs page.
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

// Only auto-connect if we're in the browser (not in SSR / test)
if (typeof window !== "undefined" && typeof EventSource !== "undefined") {
  // Delay slightly so MSW has time to start in dev mode
  setTimeout(connect, 2000);
}

export function _connectForTesting() {
  connect();
  return {
    close: () => {
      eventSource?.close();
      eventSource = null;
    },
    getEventSource: () => eventSource,
  };
}
