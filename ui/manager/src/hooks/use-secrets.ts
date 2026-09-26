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
  adoptMasterKey,
  findSecret,
  updateSecretGrant,
  grantsAllAgents,
  SecretsError,
  SECRET_EXISTS,
  SECRET_NOT_FOUND,
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

/**
 * Store a secret. Invalidates list on success.
 *
 * `createOnly` is for every "Add Secret" form: the store endpoint is an upsert,
 * so without the check a name that already exists silently replaces a live
 * credential (and, on a backend before the vault-key-safety fix, resets its
 * grant to every agent). The key is looked up in a fresh read of the tenant,
 * not the cached list, and an existing one is refused with `SECRET_EXISTS`.
 * The backend has no create-only precondition, so a second writer in the same
 * instant can still win; closing that needs one server-side.
 */
export function useStoreSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (args: {
      tenantId: string;
      keyName: string;
      value: string;
      description?: string;
      allowedAgents?: string[];
      createOnly?: boolean;
    }) => {
      if (args.createOnly && (await findSecret(args.tenantId, args.keyName))) {
        throw new SecretsError(
          `A secret named "${args.keyName}" already exists`,
          SECRET_EXISTS,
          409,
        );
      }
      return storeSecret(
        args.tenantId,
        args.keyName,
        args.value,
        args.description,
        args.allowedAgents,
      );
    },
    onSettled: (_data, _err, vars) => {
      // Settled, not success: a SECRET_EXISTS refusal means the cached list was
      // missing a key, and the operator is about to look at it again.
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

/**
 * Replace a secret's value, keeping its grant and description.
 *
 * The grant is re-read here, immediately before the write, rather than taken
 * from the row the operator clicked: that row can be minutes old, and sending a
 * stale list would undo a grant edit made in between. A key that has vanished
 * is refused with `SECRET_NOT_FOUND` instead of being re-created.
 */
export function useRotateSecret() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (args: {
      tenantId: string;
      keyName: string;
      newValue: string;
    }) => {
      const current = await findSecret(args.tenantId, args.keyName);
      if (!current) {
        throw new SecretsError(
          `Secret "${args.keyName}" no longer exists`,
          SECRET_NOT_FOUND,
          404,
        );
      }
      return rotateSecret(args.tenantId, args.keyName, args.newValue, current);
    },
    onSettled: (_data, _err, vars) => {
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

/**
 * Adopt the running master key after the previous one was lost. Every tenant's
 * listing may change (the system tenant can be reset), so everything is
 * invalidated, and health is re-checked.
 */
export function useAdoptMasterKey() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => adoptMasterKey(),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: secretKeys.all });
      qc.invalidateQueries({ queryKey: secretKeys.health });
    },
  });
}
