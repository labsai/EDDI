import { api } from "../api-client";

/* ─── Types ─── */

export interface TenantQuota {
  tenantId: string;
  maxConversationsPerDay: number;
  maxBotsPerTenant: number;
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

/* ─── API Functions ─── */

const BASE = "/administration/quotas";

/** List all tenant quotas. */
export async function listQuotas(): Promise<TenantQuota[]> {
  return api.get<TenantQuota[]>(BASE);
}

/** Get quota for a specific tenant. */
export async function getQuota(tenantId: string): Promise<TenantQuota> {
  return api.get<TenantQuota>(`${BASE}/${tenantId}`);
}

/** Update quota for a tenant. */
export async function updateQuota(
  tenantId: string,
  quota: TenantQuota,
): Promise<TenantQuota> {
  return api.put<TenantQuota>(`${BASE}/${tenantId}`, quota);
}

/** Get current usage for a tenant. */
export async function getUsage(tenantId: string): Promise<TenantUsage> {
  return api.get<TenantUsage>(`${BASE}/${tenantId}/usage`);
}

/** Reset usage counters for a tenant. */
export async function resetUsage(tenantId: string): Promise<void> {
  await api.post(`${BASE}/${tenantId}/usage/reset`, undefined);
}
