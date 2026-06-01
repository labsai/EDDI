import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  getRecentLogs,
  getHistoryLogs,
  getInstanceId,
  createLogEventSource,
  type LogEntry,
  type LogFilters,
  type HistoryFilters,
} from "@/lib/api/logs";
import type { AuthEventSourceHandle } from "@/lib/api/sse-utils";
import { useSessionLogStore } from "@/hooks/session-log-store";

// ==================== Query Keys ====================

const KEYS = {
  recent: (filters: LogFilters) => ["logs", "recent", filters] as const,
  history: (filters: HistoryFilters) => ["logs", "history", filters] as const,
  instance: ["logs", "instance"] as const,
};

// ==================== Queries ====================

export function useRecentLogs(filters: LogFilters = {}) {
  return useQuery({
    queryKey: KEYS.recent(filters),
    queryFn: () => getRecentLogs(filters),
  });
}

export function useHistoryLogs(filters: HistoryFilters = {}) {
  return useQuery({
    queryKey: KEYS.history(filters),
    queryFn: () => getHistoryLogs(filters),
  });
}

export function useInstanceId() {
  return useQuery({
    queryKey: KEYS.instance,
    queryFn: getInstanceId,
    staleTime: Infinity, // Instance ID doesn't change at runtime
  });
}

// ==================== SSE Hook ====================

const MAX_LOG_ENTRIES = 500; // Max entries in the live view

/** Are any filter fields set? */
function hasFilters(f: LogFilters): boolean {
  return !!(f.agentId || f.conversationId || f.level);
}

/**
 * Hook that subscribes to the log SSE stream for live log tailing.
 * When no filters are active, seeds initial entries from the session log store
 * (which collects since app boot) so the user sees data immediately.
 */
export function useLogStream(filters: LogFilters = {}) {
  // Seed from session store when unfiltered
  const sessionEntries = useSessionLogStore((s) => s.entries);
  const [entries, setEntries] = useState<LogEntry[]>(() =>
    hasFilters(filters) ? [] : sessionEntries.slice(0, MAX_LOG_ENTRIES)
  );
  const [sseConnected, setSseConnected] = useState(false);
  const [paused, setPaused] = useState(false);
  const handleRef = useRef<AuthEventSourceHandle | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const pausedRef = useRef(false);
  const filterKey = JSON.stringify(filters);

  // Keep ref in sync
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  const connect = useCallback(() => {
    try {
      handleRef.current?.close();
      const handle = createLogEventSource(filters, {
        onMessage: (entry) => {
          if (pausedRef.current) return;
          setEntries((prev) => {
            const next = [entry, ...prev];
            return next.length > MAX_LOG_ENTRIES
              ? next.slice(0, MAX_LOG_ENTRIES)
              : next;
          });
        },
        onOpen: () => setSseConnected(true),
        onError: () => {
          setSseConnected(false);
          reconnectTimer.current = setTimeout(connect, 5000);
        },
      });
      handleRef.current = handle;
    } catch {
      setSseConnected(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  useEffect(() => {
    connect();
    return () => {
      clearTimeout(reconnectTimer.current);
      handleRef.current?.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  const clearEntries = useCallback(() => {
    setEntries([]);
  }, []);

  return { entries, sseConnected, paused, setPaused, clearEntries };
}

