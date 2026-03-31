import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listSecrets,
  storeSecret,
  deleteSecret,
  getVaultHealth,
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
