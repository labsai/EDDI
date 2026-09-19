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
  updateSecretGrant,
  grantsAllAgents,
} from "@/lib/api/secrets";

/* ─── Query Keys ─── */

const secretKeys = {
  all: ["secrets"] as const,
  list: (tenantId: string) => ["secrets", "list", tenantId] as const,
  health: ["secrets", "health"] as const,
  /**
   * The dry-run impact of a proposed grant. Keyed on the candidate list itself,
   * so each list is asked about once and editing back to a previous one is
   * answered from cache instead of from the server.
   */
  grantImpact: (tenantId: string, keyName: string, allowedAgents: string[]) =>
    ["secrets", "grant-impact", tenantId, keyName, allowedAgents] as const,
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

/**
 * Change which agents may use a secret, leaving the value alone.
 *
 * Separate from `useStoreSecret` because it is a different operation, not a
 * cheaper spelling of the same one: no plaintext is sent, `lastRotatedAt` does
 * not move, and it works on keys whose value nobody holds any more.
 */
export function useUpdateSecretGrant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (args: {
      tenantId: string;
      keyName: string;
      allowedAgents: string[];
      description?: string;
    }) => updateSecretGrant(args),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: secretKeys.list(vars.tenantId) });
      // Every cached impact answer for this key was computed against the old
      // grant, so none of them describes reality any more. Removed rather than
      // invalidated: invalidating refetched the one the closing dialog was still
      // observing — a dry run nobody would read.
      qc.removeQueries({
        queryKey: ["secrets", "grant-impact", vars.tenantId, vars.keyName],
      });
    },
  });
}

/**
 * What a proposed grant would break, asked before it is applied.
 *
 * A dry run rather than a local computation: only the backend can tell which
 * deployed agents actually reference the secret, and it has to walk each agent's
 * workflows to find out. Disabled for the wildcard, where the answer is
 * necessarily "nothing", so an operator opening up a grant pays no round trip.
 */
export function useSecretGrantImpact(args: {
  tenantId: string;
  keyName: string;
  allowedAgents: string[];
  enabled?: boolean;
}) {
  const { tenantId, keyName, allowedAgents, enabled = true } = args;
  return useQuery({
    queryKey: secretKeys.grantImpact(tenantId, keyName ?? "", allowedAgents),
    queryFn: () =>
      updateSecretGrant({
        tenantId,
        keyName,
        allowedAgents,
        dryRun: true,
      }),
    enabled:
      enabled && allowedAgents.length > 0 && !grantsAllAgents(allowedAgents),
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
