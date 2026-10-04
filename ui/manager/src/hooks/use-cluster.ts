import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  createClusterActivitySource,
  diagnoseConversation,
  discardDeadLetters,
  drainNode,
  forwardLocalDeadLetters,
  getClusterActivity,
  getClusterDeadLetters,
  getClusterLeases,
  getClusterOverview,
  getDeadLetterSummary,
  reconcileDeployments,
  releaseLease,
  replayDeadLetters,
  resyncCaches,
  type ActivityEvent,
  type DeadLetterFilter,
} from "@/lib/api/cluster";

export const CLUSTER_KEYS = {
  all: ["cluster"] as const,
  overview: ["cluster", "overview"] as const,
  leases: (q: string, flagged: boolean) => ["cluster", "leases", q, flagged] as const,
  deadLetters: (filter: DeadLetterFilter, after: string | null) => ["cluster", "dead-letters", filter, after] as const,
  summary: ["cluster", "dead-letter-summary"] as const,
  diagnosis: (id: string) => ["cluster", "diagnosis", id] as const,
  activity: ["cluster", "activity"] as const,
};

// ==================== Queries ====================

/**
 * How long a console read may take before it counts as failed. Behind a
 * round-robin load balancer a hung or paused node keeps a request open for the
 * proxy's whole read timeout (minutes); without a bound the polling query waits
 * on it and the page silently shows numbers that stopped moving — seen live
 * with a paused node. Failing fast shows the "last refresh failed" notice, and
 * the next poll usually reaches a healthy node.
 */
export const CLUSTER_READ_TIMEOUT_MS = 8000;

export function withTimeout<T>(read: () => Promise<T>, ms = CLUSTER_READ_TIMEOUT_MS): () => Promise<T> {
  return () =>
    new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no answer within ${ms} ms`)), ms);
      read().then(
        (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        (error: unknown) => {
          clearTimeout(timer);
          reject(error);
        },
      );
    });
}

export function useClusterOverview(enabled = true) {
  return useQuery({ queryKey: CLUSTER_KEYS.overview, queryFn: withTimeout(getClusterOverview), refetchInterval: 5000, enabled });
}

export function useClusterLeases(q: string, flagged: boolean, enabled = true) {
  return useQuery({
    queryKey: CLUSTER_KEYS.leases(q, flagged),
    queryFn: withTimeout(() => getClusterLeases(q || undefined, flagged)),
    refetchInterval: 5000,
    enabled,
  });
}

export function useDeadLetterSummary(enabled = true) {
  return useQuery({ queryKey: CLUSTER_KEYS.summary, queryFn: withTimeout(getDeadLetterSummary), refetchInterval: 10000, enabled });
}

export function useClusterDeadLetters(filter: DeadLetterFilter, after: string | null, enabled = true) {
  return useQuery({
    queryKey: CLUSTER_KEYS.deadLetters(filter, after),
    queryFn: withTimeout(() => getClusterDeadLetters(filter, after)),
    refetchInterval: 15000,
    enabled,
  });
}

export function useDiagnosis(conversationId: string) {
  return useQuery({
    queryKey: CLUSTER_KEYS.diagnosis(conversationId),
    queryFn: withTimeout(() => diagnoseConversation(conversationId)),
    enabled: conversationId.length > 0,
    refetchInterval: 5000,
  });
}

// ==================== Mutations ====================

function useClusterMutation<TArg, TResult>(fn: (arg: TArg) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    // Every action changes something another panel shows: refresh the console.
    onSettled: () => qc.invalidateQueries({ queryKey: CLUSTER_KEYS.all }),
  });
}

export const useReleaseLease = () =>
  useClusterMutation((args: { conversationId: string; expectedRevision: number | null }) =>
    releaseLease(args.conversationId, args.expectedRevision),
  );
export const useReplayDeadLetters = () => useClusterMutation(replayDeadLetters);
export const useDiscardDeadLetters = () => useClusterMutation(discardDeadLetters);
export const useForwardLocalDeadLetters = () => useClusterMutation(() => forwardLocalDeadLetters());
export const useResyncCaches = () => useClusterMutation(() => resyncCaches());
export const useReconcileDeployments = () => useClusterMutation(() => reconcileDeployments());
export const useDrainNode = () => useClusterMutation((args: { nodeId: string; drain: boolean }) => drainNode(args.nodeId, args.drain));

// ==================== Activity (recent + live) ====================

/** How many entries the feed keeps — the backend's ring holds 500 too. */
export const ACTIVITY_LIMIT = 500;

/** Adds entries by id, newest first, capped. Exported for tests. */
export function mergeActivity(current: ActivityEvent[], incoming: ActivityEvent[]): ActivityEvent[] {
  if (incoming.length === 0) return current;
  const byId = new Map<string, ActivityEvent>();
  for (const e of current) byId.set(e.id, e);
  for (const e of incoming) byId.set(e.id, e);
  return [...byId.values()].sort((a, b) => b.ts - a.ts).slice(0, ACTIVITY_LIMIT);
}

export interface ClusterActivityState {
  /** Newest first. While paused, what was shown when the pause began. */
  events: ActivityEvent[];
  /** The live stream is up. */
  live: boolean;
  /** The live stream gave up retrying (e.g. refused); the recent list still loads. */
  exhausted: boolean;
  /** The recent-history read failed. */
  error: boolean;
  paused: boolean;
  /** Entries that arrived while paused; shown on resume, never dropped. */
  pending: number;
  setPaused: (paused: boolean) => void;
  refresh: () => void;
}

/**
 * The cluster activity timeline: the recent history from the REST endpoint plus
 * every new entry from the SSE stream. Pausing freezes what is shown but keeps
 * collecting — entries that arrive meanwhile appear on resume. After the stream
 * reconnects the history is read again, so entries missed while it was down are
 * filled in (the merge de-duplicates by id).
 */
export function useClusterActivity(enabled = true): ClusterActivityState {
  const [events, setEvents] = useState<ActivityEvent[]>([]);
  const [buffer, setBuffer] = useState<ActivityEvent[]>([]);
  const [paused, setPausedState] = useState(false);
  const [live, setLive] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const [error, setError] = useState(false);
  const pausedRef = useRef(false);

  const accept = useCallback((incoming: ActivityEvent[]) => {
    if (pausedRef.current) setBuffer((b) => mergeActivity(b, incoming));
    else setEvents((e) => mergeActivity(e, incoming));
  }, []);

  const refresh = useCallback(() => {
    getClusterActivity(ACTIVITY_LIMIT)
      .then((recent) => {
        setError(false);
        accept(recent);
      })
      .catch(() => setError(true));
  }, [accept]);

  useEffect(() => {
    if (!enabled) return;
    refresh();
    const source = createClusterActivitySource();
    let wasDown = false;
    source.addEventListener("activity", (event) => {
      try {
        accept([JSON.parse(event.data) as ActivityEvent]);
        setLive(true);
      } catch {
        // an unreadable entry is skipped
      }
    });
    source.addEventListener("ping", () => setLive(true));
    source.addEventListener("busy", () => {
      setLive(false);
      setExhausted(true);
      source.close();
    });
    source.onopen = () => {
      setLive(true);
      setExhausted(false);
      if (wasDown) {
        wasDown = false;
        refresh();
      }
    };
    source.onerror = () => {
      wasDown = true;
      setLive(false);
    };
    source.onexhausted = () => setExhausted(true);
    return () => source.close();
  }, [enabled, accept, refresh]);

  const setPaused = useCallback((next: boolean) => {
    pausedRef.current = next;
    setPausedState(next);
    if (!next) {
      setBuffer((b) => {
        if (b.length > 0) setEvents((e) => mergeActivity(e, b));
        return [];
      });
    }
  }, []);

  return { events, live, exhausted, error, paused, pending: buffer.length, setPaused, refresh };
}
