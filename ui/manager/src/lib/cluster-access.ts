/**
 * Who may see and do what on the cluster console.
 *
 * Mirrors the backend's `@RolesAllowed` on `IRestClusterAdmin`: operational
 * metadata (overview, leases, activity, diagnosis, dead-letter counts) is open
 * to `eddi-admin` and the read-only `eddi-viewer`; the dead-letter listing — it
 * carries captured user input — and every recovery action are `eddi-admin`
 * only. EDDI has no role hierarchy, so both roles are named.
 *
 * The backend is the authority; this only decides what to OFFER. Same
 * degradation rule as the rest of the Manager: with auth off, or with a token
 * that carries no EDDI role at all (roles mapped from another claim), the
 * Manager offers everything and lets the backend answer.
 */

export const ROLE_ADMIN = "eddi-admin";
export const ROLE_VIEWER = "eddi-viewer";

const EDDI_ROLES = ["eddi-admin", "eddi-editor", "eddi-user", "eddi-viewer", "eddi-approver"];

export interface ClusterAccess {
  /** May open the console at all (overview, leases, activity, diagnosis). */
  canView: boolean;
  /** May read dead-letter content and take recovery actions. */
  canAct: boolean;
}

export function clusterAccess(roles: readonly string[], authMethod: string): ClusterAccess {
  if (authMethod === "none" || !roles.some((r) => EDDI_ROLES.includes(r))) {
    return { canView: true, canAct: true };
  }
  const admin = roles.includes(ROLE_ADMIN);
  return { canView: admin || roles.includes(ROLE_VIEWER), canAct: admin };
}
