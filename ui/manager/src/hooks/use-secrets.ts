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
  list: (tenantId: string, agentId: string) =>
    ["secrets", "list", tenantId, agentId] as const,
  health: ["secrets", "health"] as const,
};

/* ─── Hooks ─── */

/** List secrets for a tenant+agent namespace. */
export function useSecrets(tenantId: string, agentId: string) {
  return useQuery({
    queryKey: secretKeys.list(tenantId, agentId),
    queryFn: () => listSecrets(tenantId, agentId),
    enabled: !!tenantId && !!agentId,
  });
}

/** Store (create or update) a secret. Invalidates list on success. */
export function useStoreSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      tenantId: string;
      agentId: string;
      keyName: string;
      value: string;
    }) => storeSecret(args.tenantId, args.agentId, args.keyName, args.value),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId, vars.agentId),
      });
    },
  });
}

/** Delete a secret. Invalidates list on success. */
export function useDeleteSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      tenantId: string;
      agentId: string;
      keyName: string;
    }) => deleteSecret(args.tenantId, args.agentId, args.keyName),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId, vars.agentId),
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
