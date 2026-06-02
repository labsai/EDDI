import { create } from "zustand";
import { createLogEventSource, type LogEntry } from "@/lib/api/logs";
import type { AuthEventSourceHandle } from "@/lib/api/sse-utils";
import { SSE_RECONNECT_BASE_MS, SSE_RECONNECT_MAX_ATTEMPTS, SSE_RECONNECT_MAX_DELAY_MS } from "@/lib/constants";

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

let handle: AuthEventSourceHandle | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
let reconnectAttempts = 0;

function connect() {
  try {
    // Close any existing stream before opening a new one
    handle?.close();
    handle = null;
    clearTimeout(reconnectTimer);

    handle = createLogEventSource(
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
          reconnectAttempts = 0;
          useSessionLogStore.setState({ connected: true });
        },
        onError: () => {
          useSessionLogStore.setState({ connected: false });
          handle?.close();
          handle = null;
          if (reconnectAttempts < SSE_RECONNECT_MAX_ATTEMPTS) {
            const delay = Math.min(SSE_RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts), SSE_RECONNECT_MAX_DELAY_MS);
            reconnectAttempts++;
            clearTimeout(reconnectTimer);
            reconnectTimer = setTimeout(connect, delay);
          }
        },
      },
    );
  } catch {
    useSessionLogStore.setState({ connected: false });
  }
}

// Only auto-connect if we're in the browser (not in SSR / test).
// Vitest sets import.meta.env.MODE to "test"; without this guard the JSDOM
// environment would schedule a real setTimeout and try to open an SSE stream.
if (typeof window !== "undefined" && import.meta.env.MODE !== "test") {
  // Delay slightly so MSW has time to start in dev mode
  setTimeout(connect, 2000);
}
