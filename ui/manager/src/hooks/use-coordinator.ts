import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  getCoordinatorStatus,
  getDeadLetters,
  replayDeadLetter,
  discardDeadLetter,
  purgeDeadLetters,
  createCoordinatorEventSource,
  type CoordinatorStatus,
} from "@/lib/api/coordinator";
import type { AuthEventSourceHandle } from "@/lib/api/sse-utils";

// ==================== Query Keys ====================

const KEYS = {
  status: ["coordinator", "status"] as const,
  deadLetters: ["coordinator", "dead-letters"] as const,
};

// ==================== Queries ====================

export function useCoordinatorStatus() {
  return useQuery({
    queryKey: KEYS.status,
    queryFn: getCoordinatorStatus,
    refetchInterval: 5000,
  });
}

export function useDeadLetters() {
  return useQuery({
    queryKey: KEYS.deadLetters,
    queryFn: getDeadLetters,
    refetchInterval: 10000,
  });
}

// ==================== Mutations ====================

export function useReplayDeadLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: replayDeadLetter,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.deadLetters });
      qc.invalidateQueries({ queryKey: KEYS.status });
    },
  });
}

export function useDiscardDeadLetter() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: discardDeadLetter,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.deadLetters });
      qc.invalidateQueries({ queryKey: KEYS.status });
    },
  });
}

export function usePurgeDeadLetters() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: purgeDeadLetters,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEYS.deadLetters });
      qc.invalidateQueries({ queryKey: KEYS.status });
    },
  });
}

// ==================== SSE Hook ====================

/** A timestamped snapshot from the SSE stream */
export interface CoordinatorSnapshot extends CoordinatorStatus {
  /** When this snapshot was received */
  receivedAt: string;
}

const EVENT_HISTORY_LIMIT = 20;

/**
 * Hook that subscribes to the coordinator SSE stream for live status updates.
 * Falls back to polling via useCoordinatorStatus if SSE is not available.
 * Buffers the last 20 status snapshots as a compact event history.
 */
export function useCoordinatorSSE() {
  const [liveStatus, setLiveStatus] = useState<CoordinatorStatus | null>(null);
  const [sseConnected, setSseConnected] = useState(false);
  const [eventHistory, setEventHistory] = useState<CoordinatorSnapshot[]>([]);
  const handleRef = useRef<AuthEventSourceHandle | null>(null);

  const connect = useCallback(() => {
    try {
      const handle = createCoordinatorEventSource({
        onMessage: (data) => {
          setLiveStatus(data);
          setSseConnected(true);

          // Buffer the snapshot into event history
          const snapshot: CoordinatorSnapshot = {
            ...data,
            receivedAt: new Date().toISOString(),
          };
          setEventHistory((prev) => {
            const next = [...prev, snapshot];
            return next.length > EVENT_HISTORY_LIMIT
              ? next.slice(-EVENT_HISTORY_LIMIT)
              : next;
          });
        },
        onOpen: () => setSseConnected(true),
        onError: () => {
          setSseConnected(false);
          // Reconnect after 5 seconds
          setTimeout(connect, 5000);
        },
      });
      handleRef.current = handle;
    } catch {
      setSseConnected(false);
    }
  }, []);

  useEffect(() => {
    connect();
    return () => {
      handleRef.current?.close();
    };
  }, [connect]);

  return { liveStatus, sseConnected, eventHistory };
}

