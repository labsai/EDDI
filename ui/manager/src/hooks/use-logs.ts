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

/**
 * Hook that subscribes to the log SSE stream for live log tailing.
 * Returns accumulated log entries and connection status.
 */
export function useLogStream(filters: LogFilters = {}) {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [sseConnected, setSseConnected] = useState(false);
  const [paused, setPaused] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const pausedRef = useRef(false);
  const filterKey = JSON.stringify(filters);

  // Keep ref in sync
  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  const connect = useCallback(() => {
    try {
      const es = createLogEventSource(filters);
      eventSourceRef.current = es;

      es.addEventListener("log", (event) => {
        if (pausedRef.current) return;
        try {
          const entry = JSON.parse(event.data) as LogEntry;
          setEntries((prev) => {
            const next = [entry, ...prev];
            return next.length > MAX_LOG_ENTRIES
              ? next.slice(0, MAX_LOG_ENTRIES)
              : next;
          });
        } catch {
          // ignore parse errors
        }
      });

      es.onerror = () => {
        setSseConnected(false);
        es.close();
        setTimeout(connect, 5000);
      };

      es.onopen = () => {
        setSseConnected(true);
      };
    } catch {
      setSseConnected(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  useEffect(() => {
    connect();
    return () => {
      eventSourceRef.current?.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  const clearEntries = useCallback(() => {
    setEntries([]);
  }, []);

  return { entries, sseConnected, paused, setPaused, clearEntries };
}
