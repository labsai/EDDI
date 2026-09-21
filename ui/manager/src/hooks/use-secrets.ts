import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listSecrets,
  storeSecret,
  deleteSecret,
  getVaultHealth,
  rotateSecret,
  rotateDek,
  rotateKek,
  resetTenant,
} from "@/lib/api/secrets";

/* ─── Query Keys ─── */

const secretKeys = {
  all: ["secrets"] as const,
  list: (tenantId: string) => ["secrets", "list", tenantId] as const,
  health: ["secrets", "health"] as const,
};

/* ─── Hooks ─── */

/** List secrets for a tenant. */
export function useSecrets(tenantId: string) {
  return useQuery({
    queryKey: secretKeys.list(tenantId),
    queryFn: () => listSecrets(tenantId),
    enabled: !!tenantId,
  });
}

/** Store (create or update) a secret. Invalidates list on success. */
export function useStoreSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      tenantId: string;
      keyName: string;
      value: string;
      description?: string;
      allowedAgents?: string[];
    }) =>
      storeSecret(
        args.tenantId,
        args.keyName,
        args.value,
        args.description,
        args.allowedAgents,
      ),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId),
      });
    },
  });
}

/** Delete a secret. Invalidates list on success. */
export function useDeleteSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: { tenantId: string; keyName: string }) =>
      deleteSecret(args.tenantId, args.keyName),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId),
      });
    },
  });
}

/** Vault health check. */
export function useVaultHealth() {
  return useQuery({
    queryKey: secretKeys.health,
    queryFn: getVaultHealth,
    refetchInterval: 30_000, // poll every 30s
  });
}

/** Rotate a secret — store a new value via the rotation endpoint. */
export function useRotateSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      tenantId: string;
      keyName: string;
      newValue: string;
      description?: string;
    }) =>
      rotateSecret(
        args.tenantId,
        args.keyName,
        args.newValue,
        args.description,
      ),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId),
      });
    },
  });
}

/**
 * Rotate the tenant's Data Encryption Key (DEK). Safe/no-restart — re-encrypts
 * the tenant's secrets. Invalidates the tenant list (rotation timestamps change).
 */
export function useRotateDek() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: { tenantId: string }) => rotateDek(args.tenantId),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId),
      });
    },
  });
}

/**
 * Rotate the master key (KEK). Re-encrypts every tenant's DEK, so invalidate all
 * secret queries and re-check vault health.
 */
export function useRotateKek() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: { oldKey: string; newKey: string }) =>
      rotateKek({ oldKey: args.oldKey, newKey: args.newKey }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: secretKeys.all });
      qc.invalidateQueries({ queryKey: secretKeys.health });
    },
  });
}

/**
 * Reset the vault for a tenant. DESTRUCTIVE — deletes all secrets and the DEK.
 * Invalidates the tenant list and all secret queries.
 */
export function useResetTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: { tenantId: string }) => resetTenant(args.tenantId),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId),
      });
      qc.invalidateQueries({ queryKey: secretKeys.all });
    },
  });
}
