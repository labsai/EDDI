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
import { SSE_RECONNECT_BASE_MS, SSE_RECONNECT_MAX_ATTEMPTS, SSE_RECONNECT_MAX_DELAY_MS } from "@/lib/constants";

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
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const reconnectAttempts = useRef(0);

  const connect = useCallback(() => {
    try {
      handleRef.current?.close();
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
        onOpen: () => {
          reconnectAttempts.current = 0;
          setSseConnected(true);
        },
        onError: () => {
          setSseConnected(false);
          if (reconnectAttempts.current < SSE_RECONNECT_MAX_ATTEMPTS) {
            const delay = Math.min(SSE_RECONNECT_BASE_MS * Math.pow(2, reconnectAttempts.current), SSE_RECONNECT_MAX_DELAY_MS);
            reconnectAttempts.current++;
            clearTimeout(reconnectTimer.current);
            reconnectTimer.current = setTimeout(connect, delay);
          }
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
      clearTimeout(reconnectTimer.current);
      handleRef.current?.close();
    };
  }, [connect]);

  return { liveStatus, sseConnected, eventHistory };
}

