import { api, isApiError } from "../api-client";

/* ─── Types ─── */

export interface TenantQuota {
  tenantId: string;
  maxConversationsPerDay: number;
  maxAgentsPerTenant: number;
  maxApiCallsPerMinute: number;
  maxMonthlyCostUsd: number;
  enabled: boolean;
}

export interface TenantUsage {
  tenantId: string;
  conversationsToday: number;
  apiCallsThisMinute: number;
  monthlyCostUsd: number;
  minuteWindowStart: string;
  dayStart: string;
}

/* ─── Helpers ─── */

/** Sensible defaults when no quota record exists yet (all unlimited, disabled). */
export function defaultQuota(tenantId: string): TenantQuota {
  return {
    tenantId,
    maxConversationsPerDay: -1,
    maxAgentsPerTenant: -1,
    maxApiCallsPerMinute: -1,
    maxMonthlyCostUsd: -1,
    enabled: false,
  };
}

/** Zeroed usage snapshot for tenants with no usage data yet. */
export function emptyUsage(tenantId: string): TenantUsage {
  return {
    tenantId,
    conversationsToday: 0,
    apiCallsThisMinute: 0,
    monthlyCostUsd: 0,
    minuteWindowStart: new Date().toISOString(),
    dayStart: new Date().toISOString(),
  };
}

/* ─── API Functions ─── */

const BASE = "/administration/quotas";

/** List all tenant quotas. */
export async function listQuotas(): Promise<TenantQuota[]> {
  return api.get<TenantQuota[]>(BASE);
}

/**
 * Get quota for a specific tenant.
 * Returns local defaults when no record exists (404) so the UI can render.
 */
export async function getQuota(tenantId: string): Promise<TenantQuota> {
  try {
    return await api.get<TenantQuota>(`${BASE}/${tenantId}`);
  } catch (err) {
    if (isApiError(err) && err.status === 404) {
      return defaultQuota(tenantId);
    }
    throw err;
  }
}

/** Update (or create) quota for a tenant. */
export async function updateQuota(
  tenantId: string,
  quota: TenantQuota,
): Promise<TenantQuota> {
  return api.put<TenantQuota>(`${BASE}/${tenantId}`, quota);
}

/**
 * Get current usage for a tenant.
 * Returns zeroed snapshot when no usage data exists yet (404).
 */
export async function getUsage(tenantId: string): Promise<TenantUsage> {
  try {
    return await api.get<TenantUsage>(`${BASE}/${tenantId}/usage`);
  } catch (err) {
    if (isApiError(err) && err.status === 404) {
      return emptyUsage(tenantId);
    }
    throw err;
  }
}

/** Reset usage counters for a tenant. */
export async function resetUsage(tenantId: string): Promise<void> {
  await api.post(`${BASE}/${tenantId}/usage/reset`, undefined);
}

