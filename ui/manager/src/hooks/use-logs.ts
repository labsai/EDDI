import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  getRecentLogs,
  getHistoryLogs,
  getInstanceId,
  createLogEventSource,
  type LogEntry,
  type LogFilters,
  type HistoryFilters,
  type DatabaseLogEntry,
} from "@/lib/api/logs";
import type { BearerEventSource } from "@/lib/bearer-event-source";
import { useSessionLogStore, connect as connectSessionLogStream } from "@/hooks/session-log-store";
import { historyEntryKey, mergeNewestFirst, newByMultiplicity } from "@/lib/log-entries";

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

/** Rows per history page. The backend defaults to 100 as well. */
export const HISTORY_PAGE_SIZE = 100;

/**
 * Database log history, newest first, one page at a time.
 *
 * This used to be a single query for `limit=100` with no way to ask for more,
 * so the History tab could never show anything older than the newest 100 rows
 * of a filter — exactly where an operator goes looking for what happened
 * yesterday. `skip` is a row offset on both backends, so each further page asks
 * for the next `limit` rows. Rows written between two page loads shift the
 * window, so pages are merged de-duplicated.
 */
export function useHistoryLogs(filters: HistoryFilters = {}) {
  const pageSize = filters.limit ?? HISTORY_PAGE_SIZE;
  const startSkip = filters.skip ?? 0;
  const query = useInfiniteQuery({
    queryKey: KEYS.history(filters),
    initialPageParam: startSkip,
    queryFn: ({ pageParam }) =>
      getHistoryLogs({ ...filters, skip: pageParam, limit: pageSize }),
    // A short page is the last page.
    getNextPageParam: (lastPage, allPages) =>
      lastPage.length < pageSize
        ? undefined
        : startSkip + allPages.length * pageSize,
  });

  const data = useMemo(() => {
    if (!query.data) return undefined;
    // Rows written between two page loads shift the window, so a page can
    // repeat rows from the end of the previous one. Counted by multiplicity so
    // genuinely repeated lines inside a page survive.
    let rows: DatabaseLogEntry[] = [];
    for (const page of query.data.pages) {
      rows = rows.concat(newByMultiplicity(rows, page, historyEntryKey));
    }
    return rows;
  }, [query.data]);

  const { fetchNextPage } = query;
  const loadMore = useCallback(() => {
    void fetchNextPage();
  }, [fetchNextPage]);

  return {
    data,
    isLoading: query.isLoading,
    isError: query.isError,
    isSuccess: query.isSuccess,
    error: query.error,
    refetch: query.refetch,
    hasMore: query.hasNextPage,
    isLoadingMore: query.isFetchingNextPage,
    loadMore,
  };
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
const FILTERED_RESEED_LIMIT = 200;

/** Are any filter fields set? */
function hasFilters(f: LogFilters): boolean {
  return !!(f.agentId || f.conversationId || f.level);
}

/**
 * Hook that subscribes to the log SSE stream for live log tailing.
 * When no filters are active, delegates entirely to the session log store
 * (which collects since app boot) — no redundant SSE connection.
 * Only opens a dedicated SSE connection when filters are set.
 */
export function useLogStream(filters: LogFilters = {}) {
  const filtered = hasFilters(filters);

  // ── Session store path (unfiltered) ──────────────────────────
  const sessionEntries = useSessionLogStore((s) => s.entries);
  const sessionConnected = useSessionLogStore((s) => s.connected);
  const sessionSeeded = useSessionLogStore((s) => s.seeded);

  /*
   * Hold the unfiltered stream open only while this hook is mounted AND
   * unfiltered. The store used to connect at import time from `main.tsx`, so
   * every Manager tab kept an SSE connection open on every page for its whole
   * lifetime — and EDDI serves HTTP/1.1, where Chrome allows six concurrent
   * connections per origin across the entire profile. A couple of tabs
   * saturated the cap and pages hung on skeleton loaders while the server was
   * fine. The store refcounts, so two viewers share one socket.
   */
  useEffect(() => {
    if (filtered) return;
    return connectSessionLogStream();
  }, [filtered]);

  // ── Filtered SSE path ────────────────────────────────────────
  const [filteredEntries, setFilteredEntries] = useState<LogEntry[]>([]);
  const [filteredConnected, setFilteredConnected] = useState(false);
  const eventSourceRef = useRef<BearerEventSource | null>(null);
  const filterKey = JSON.stringify(filters);

  const connect = useCallback(() => {
    try {
      const es = createLogEventSource(filters);
      eventSourceRef.current = es;

      // Lines keep arriving while the view is paused — pausing freezes what is
      // SHOWN (below), it does not drop what is logged meanwhile. Dropping them
      // made "Resume" silently skip everything logged during the pause.
      const handleEvent = (event: MessageEvent) => {
        try {
          const entry = JSON.parse(event.data) as LogEntry;
          // De-duplicated: every (re)connect replays up to 50 recent lines.
          setFilteredEntries((prev) =>
            mergeNewestFirst(prev, [entry], MAX_LOG_ENTRIES)
          );
        } catch {
          // ignore parse errors
        }
      };

      es.addEventListener("log", handleEvent);
      // Fallback for backends that send unnamed SSE events
      es.onmessage = handleEvent;

      // BearerEventSource reconnects on the bounded policy in
      // `sse-reconnect.ts`. Closing it here and re-creating it on an unbounded
      // `setTimeout(connect, 5000)` — as this used to — both duplicated that
      // mechanism and defeated its attempt cap, because each new instance got a
      // fresh budget. A filtered stream the backend refuses now backs off and
      // eventually stops instead of retrying for the life of the page.
      es.onerror = () => {
        setFilteredConnected(false);
      };

      es.onopen = () => {
        setFilteredConnected(true);
        // Same gap as the session store: what was logged while the stream was
        // down never arrives on it, so re-fetch the ring buffer on every open.
        getRecentLogs({ ...filters, limit: FILTERED_RESEED_LIMIT })
          .then((recent) =>
            setFilteredEntries((prev) =>
              mergeNewestFirst(prev, recent, MAX_LOG_ENTRIES)
            )
          )
          .catch(() => {
            /* the live stream still works; the gap just stays unfilled */
          });
      };
    } catch {
      setFilteredConnected(false);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey]);

  // Only open a dedicated SSE when filters are active
  useEffect(() => {
    if (!filtered) return;
    setFilteredEntries([]); // reset on filter change
    setFilteredConnected(false); // reset connection state before reconnecting
    connect();
    return () => {
      eventSourceRef.current?.close();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterKey, filtered]);

  // Close SSE when switching from filtered → unfiltered
  useEffect(() => {
    if (!filtered && eventSourceRef.current) {
      eventSourceRef.current.close();
      eventSourceRef.current = null;
    }
  }, [filtered]);

  // Pause freezes the DISPLAYED list in both modes. It used to be implemented
  // only on the filtered path (by discarding events), so in the default
  // unfiltered view — which reads the shared session store — the button toggled
  // its label and the list kept scrolling underneath.
  const [pausedSnapshot, setPausedSnapshot] = useState<LogEntry[] | null>(null);
  const paused = pausedSnapshot !== null;

  const liveEntries = useMemo(
    () => (filtered ? filteredEntries : sessionEntries.slice(0, MAX_LOG_ENTRIES)),
    [filtered, filteredEntries, sessionEntries]
  );

  const setPaused = useCallback(
    (next: boolean) => setPausedSnapshot(next ? liveEntries : null),
    [liveEntries]
  );

  // A different filter is a different list; a snapshot of the old one would
  // show results that do not match the filter bar.
  const [snapshotFilterKey, setSnapshotFilterKey] = useState(filterKey);
  if (snapshotFilterKey !== filterKey) {
    setSnapshotFilterKey(filterKey);
    setPausedSnapshot(null);
  }

  const clearEntries = useCallback(() => {
    if (filtered) {
      setFilteredEntries([]);
    } else {
      // For unfiltered, clearing just resets the session store
      useSessionLogStore.setState({ entries: [] });
    }
    setPausedSnapshot((s) => (s === null ? null : []));
  }, [filtered]);

  return {
    entries: pausedSnapshot ?? liveEntries,
    sseConnected: filtered ? filteredConnected : sessionConnected,
    seeded: filtered ? true : sessionSeeded,
    paused,
    setPaused,
    clearEntries,
  };
}
