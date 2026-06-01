import { create } from "zustand";
import { createLogEventSource, type LogEntry } from "@/lib/api/logs";

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
}

export const useSessionLogStore = create<SessionLogState>(() => ({
  entries: [],
  connected: false,
}));

// ─── Auto-connect SSE on module load ─────────────────────────────────────────

function connect() {
  try {
    createLogEventSource(
      {}, // no filters — capture everything
      {
        onMessage: (entry) => {
          useSessionLogStore.setState((s) => {
            const next = [entry, ...s.entries];
            return {
              entries:
                next.length > MAX_SESSION_ENTRIES
                  ? next.slice(0, MAX_SESSION_ENTRIES)
                  : next,
            };
          });
        },
        onOpen: () => {
          useSessionLogStore.setState({ connected: true });
        },
        onError: () => {
          useSessionLogStore.setState({ connected: false });
          // Reconnect after 5s
          setTimeout(connect, 5000);
        },
      },
    );
  } catch {
    useSessionLogStore.setState({ connected: false });
  }
}

// Only auto-connect if we're in the browser (not in SSR / test)
if (typeof window !== "undefined") {
  // Delay slightly so MSW has time to start in dev mode
  setTimeout(connect, 2000);
}

