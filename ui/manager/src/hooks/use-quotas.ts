import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listQuotas,
  getQuota,
  updateQuota,
  getUsage,
  resetUsage,
} from "@/lib/api/quotas";
import type { TenantQuota } from "@/lib/api/quotas";

/* ─── Query Keys ─── */

const quotaKeys = {
  all: ["quotas"] as const,
  list: ["quotas", "list"] as const,
  detail: (tenantId: string) => ["quotas", "detail", tenantId] as const,
  usage: (tenantId: string) => ["quotas", "usage", tenantId] as const,
};

/* ─── Hooks ─── */

/** List all tenant quotas. */
export function useQuotas() {
  return useQuery({
    queryKey: quotaKeys.list,
    queryFn: listQuotas,
  });
}

/** Get quota for a single tenant. */
export function useQuota(tenantId: string) {
  return useQuery({
    queryKey: quotaKeys.detail(tenantId),
    queryFn: () => getQuota(tenantId),
    enabled: !!tenantId,
  });
}

/** Get usage for a tenant. */
export function useQuotaUsage(tenantId: string) {
  return useQuery({
    queryKey: quotaKeys.usage(tenantId),
    queryFn: () => getUsage(tenantId),
    enabled: !!tenantId,
    refetchInterval: 10_000, // poll every 10s for live usage
  });
}

/** Update a tenant's quota. */
export function useUpdateQuota() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: { tenantId: string; quota: TenantQuota }) =>
      updateQuota(args.tenantId, args.quota),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: quotaKeys.detail(vars.tenantId) });
      qc.invalidateQueries({ queryKey: quotaKeys.list });
    },
  });
}

/** Reset usage counters. */
export function useResetUsage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (tenantId: string) => resetUsage(tenantId),
    onSuccess: (_data, tenantId) => {
      qc.invalidateQueries({ queryKey: quotaKeys.usage(tenantId) });
    },
  });
}
