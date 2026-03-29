import { useQuery } from "@tanstack/react-query";
import { useState, useCallback, useRef, useEffect } from "react";

// ─── Types ───────────────────────────────────────────────────────

export interface PlatformStatus {
  /** Current connection state */
  status: "checking" | "online" | "offline";
  /** EDDI instance identifier (from /administration/logs/instance) */
  instanceId: string | null;
  /** Last measured round-trip latency in ms */
  latencyMs: number | null;
  /** When the last successful or failed check occurred */
  lastCheckedAt: Date | null;
}

interface InstanceResponse {
  instanceId: string;
}

// ─── Fetch with latency measurement ──────────────────────────────

async function checkPlatformHealth(): Promise<{
  instanceId: string;
  latencyMs: number;
}> {
  const start = performance.now();
  const res = await fetch(
    `${window.location.origin}/administration/logs/instance`,
    { signal: AbortSignal.timeout(5000) },
  );
  const latencyMs = Math.round(performance.now() - start);

  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }

  const data = (await res.json()) as InstanceResponse;
  return { instanceId: data.instanceId, latencyMs };
}

// ─── Hook ────────────────────────────────────────────────────────

const QUERY_KEY = ["platform", "health"] as const;

/**
 * Global platform health hook.
 * Polls /administration/logs/instance every 15s.
 * Returns connection status, instance ID, and latency.
 */
export function usePlatformStatus(): PlatformStatus {
  const [latencyMs, setLatencyMs] = useState<number | null>(null);
  const lastCheckedRef = useRef<Date | null>(null);
  const [lastCheckedAt, setLastCheckedAt] = useState<Date | null>(null);

  const onSuccess = useCallback((data: { instanceId: string; latencyMs: number }) => {
    setLatencyMs(data.latencyMs);
    lastCheckedRef.current = new Date();
    setLastCheckedAt(lastCheckedRef.current);
  }, []);

  const onError = useCallback(() => {
    setLatencyMs(null);
    lastCheckedRef.current = new Date();
    setLastCheckedAt(lastCheckedRef.current);
  }, []);

  const query = useQuery({
    queryKey: QUERY_KEY,
    queryFn: async () => {
      const result = await checkPlatformHealth();
      onSuccess(result);
      return result;
    },
    refetchInterval: 15_000,
    staleTime: 10_000,
    retry: false,
  });

  // Track errors via effect since TanStack Query v5 removed onError callback
  useEffect(() => {
    if (query.isError) {
      onError();
    }
  }, [query.isError, query.errorUpdatedAt, onError]);

  const status: PlatformStatus["status"] = query.isLoading
    ? "checking"
    : query.isError
      ? "offline"
      : "online";

  return {
    status,
    instanceId: query.data?.instanceId ?? null,
    latencyMs: status === "online" ? latencyMs : null,
    lastCheckedAt,
  };
}
