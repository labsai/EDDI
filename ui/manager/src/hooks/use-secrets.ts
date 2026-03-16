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
  list: (tenantId: string, botId: string) =>
    ["secrets", "list", tenantId, botId] as const,
  health: ["secrets", "health"] as const,
};

/* ─── Hooks ─── */

/** List secrets for a tenant+bot namespace. */
export function useSecrets(tenantId: string, botId: string) {
  return useQuery({
    queryKey: secretKeys.list(tenantId, botId),
    queryFn: () => listSecrets(tenantId, botId),
    enabled: !!tenantId && !!botId,
  });
}

/** Store (create or update) a secret. Invalidates list on success. */
export function useStoreSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      tenantId: string;
      botId: string;
      keyName: string;
      value: string;
    }) => storeSecret(args.tenantId, args.botId, args.keyName, args.value),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId, vars.botId),
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
      botId: string;
      keyName: string;
    }) => deleteSecret(args.tenantId, args.botId, args.keyName),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({
        queryKey: secretKeys.list(vars.tenantId, vars.botId),
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
