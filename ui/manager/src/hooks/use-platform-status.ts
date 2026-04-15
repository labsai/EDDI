import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";

// ─── Types ───────────────────────────────────────────────────────

export interface PlatformStatus {
  /** Current connection state */
  status: "checking" | "online" | "offline";
  /** EDDI instance identifier (from /administration/logs/instance-id) */
  instanceId: string | null;
  /** Last measured round-trip latency in ms */
  latencyMs: number | null;
  /** When the last successful or failed check occurred */
  lastCheckedAt: Date | null;
}

interface HealthResult {
  instanceId: string;
  latencyMs: number;
}

// ─── Fetch with latency measurement ──────────────────────────────

async function checkPlatformHealth(): Promise<HealthResult> {
  const start = performance.now();
  const res = await fetch(
    `${window.location.origin}/administration/logs/instance-id`,
    { signal: AbortSignal.timeout(5000) },
  );
  const latencyMs = Math.round(performance.now() - start);

  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }

  const data = (await res.json()) as { instanceId: string };
  return { instanceId: data.instanceId, latencyMs };
}

// ─── Hook ────────────────────────────────────────────────────────

const QUERY_KEY = ["platform", "health"] as const;

/**
 * Global platform health hook.
 * Polls /administration/logs/instance-id every 15s.
 * Returns connection status, instance ID, and latency.
 *
 * All derived state is computed from the TanStack Query result
 * — no extra useState — so each poll causes exactly one render.
 */
export function usePlatformStatus(): PlatformStatus {
  const query = useQuery({
    queryKey: QUERY_KEY,
    queryFn: checkPlatformHealth,
    refetchInterval: 15_000,
    staleTime: 10_000,
    retry: 1,
  });

  // Use dataUpdatedAt / errorUpdatedAt as a stable epoch for "last checked"
  const lastTimestamp = query.dataUpdatedAt || query.errorUpdatedAt;

  const status: PlatformStatus["status"] = query.isLoading
    ? "checking"
    : query.isError
      ? "offline"
      : "online";

  return useMemo<PlatformStatus>(() => ({
    status,
    instanceId: query.data?.instanceId ?? null,
    latencyMs: status === "online" ? (query.data?.latencyMs ?? null) : null,
    lastCheckedAt: lastTimestamp ? new Date(lastTimestamp) : null,
  }), [status, query.data, lastTimestamp]);
}
