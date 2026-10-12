import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { api } from "@/lib/api-client";
import { useIsAdminOrUnknown } from "@/hooks/use-auth";

// ─── Types ───────────────────────────────────────────────────────

export interface PlatformStatus {
  /** Current connection state */
  status: "checking" | "online" | "offline";
  /**
   * EDDI instance identifier (from /administration/logs/instance-id).
   * Null for callers without `eddi-admin` — the endpoint refuses them.
   */
  instanceId: string | null;
  /** Last measured round-trip latency in ms */
  latencyMs: number | null;
  /** When the last successful or failed check occurred */
  lastCheckedAt: Date | null;
}

interface HealthResult {
  instanceId: string | null;
  latencyMs: number;
}

// ─── Fetch with latency measurement ──────────────────────────────

/**
 * The probe for a user whose token shows EDDI roles WITHOUT eddi-admin: the
 * docs index (`IRestDocs`), which every EDDI role may read and which is a list
 * of names. The admin probe answered such a user with a 403 every 15 seconds —
 * harmless, but a red line in the console per poll.
 */
const NON_ADMIN_PROBE = "/administration/docs";

async function checkPlatformHealth(adminProbe: boolean): Promise<HealthResult> {
  const start = performance.now();
  const res = await fetch(
    `${window.location.origin}${adminProbe ? "/administration/logs/instance-id" : NON_ADMIN_PROBE}`,
    { signal: AbortSignal.timeout(5000), headers: api.getAuthHeader() },
  );
  const latencyMs = Math.round(performance.now() - start);

  // The instance-id endpoint is eddi-admin only. A 401/403 is still an
  // answer from EDDI, so the platform is reachable — this caller just may
  // not see which node answered. Reporting that as "Offline" told every
  // eddi-editor/eddi-viewer the backend was down while they were using it.
  if (res.status === 401 || res.status === 403) {
    return { instanceId: null, latencyMs };
  }

  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }

  if (!adminProbe) return { instanceId: null, latencyMs };

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
 * The probe is an admin endpoint rather than /q/health on purpose: /q/* is
 * not proxied by the Vite dev server and is commonly blocked at the ingress,
 * and the admin endpoint is what yields the instance ID admins see. Any
 * answer from it — including 401/403 for non-admins — means "online"; only
 * a network failure, timeout or other non-2xx status means "offline".
 *
 * All derived state is computed from the TanStack Query result
 * — no extra useState — so each poll causes exactly one render.
 */
export function usePlatformStatus(): PlatformStatus {
  const adminProbe = useIsAdminOrUnknown();
  const query = useQuery({
    queryKey: [...QUERY_KEY, adminProbe ? "admin" : "any-role"],
    queryFn: () => checkPlatformHealth(adminProbe),
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
