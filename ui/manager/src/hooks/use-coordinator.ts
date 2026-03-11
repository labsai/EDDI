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

/**
 * Hook that subscribes to the coordinator SSE stream for live status updates.
 * Falls back to polling via useCoordinatorStatus if SSE is not available.
 */
export function useCoordinatorSSE() {
  const [liveStatus, setLiveStatus] = useState<CoordinatorStatus | null>(null);
  const [sseConnected, setSseConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  const connect = useCallback(() => {
    try {
      const es = createCoordinatorEventSource();
      eventSourceRef.current = es;

      es.addEventListener("status", (event) => {
        try {
          const data = JSON.parse(event.data) as CoordinatorStatus;
          setLiveStatus(data);
          setSseConnected(true);
        } catch {
          // ignore parse errors
        }
      });

      es.onerror = () => {
        setSseConnected(false);
        es.close();
        // Reconnect after 5 seconds
        setTimeout(connect, 5000);
      };

      es.onopen = () => {
        setSseConnected(true);
      };
    } catch {
      setSseConnected(false);
    }
  }, []);

  useEffect(() => {
    connect();
    return () => {
      eventSourceRef.current?.close();
    };
  }, [connect]);

  return { liveStatus, sseConnected };
}
