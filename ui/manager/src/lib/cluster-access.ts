import { mayDo } from "@eddi/app-kit/lib/roles";

/**
 * Who may see and do what on the cluster console.
 *
 * Reads the permission map (`cluster.read`, `cluster.act` in
 * `@eddi/app-kit/lib/roles`), which mirrors the backend's `@RolesAllowed` on
 * `IRestClusterAdmin`: operational metadata (overview, leases, activity,
 * diagnosis, dead-letter counts) is open to `eddi-admin` and the read-only
 * `eddi-viewer`; the dead-letter listing — it carries captured user input — and
 * every recovery action are `eddi-admin` only.
 *
 * The backend is the authority; this only decides what to OFFER. Same
 * degradation rule as the rest of the Manager: with auth off, or with a token
 * that carries no EDDI role at all (roles mapped from another claim), the
 * Manager offers everything and lets the backend answer.
 */

export interface ClusterAccess {
  /** May open the console at all (overview, leases, activity, diagnosis). */
  canView: boolean;
  /** May read dead-letter content and take recovery actions. */
  canAct: boolean;
}

export function clusterAccess(roles: readonly string[], authMethod: string): ClusterAccess {
  const authDisabled = authMethod === "none";
  return {
    canView: mayDo("cluster.read", roles, authDisabled),
    canAct: mayDo("cluster.act", roles, authDisabled),
  };
}
