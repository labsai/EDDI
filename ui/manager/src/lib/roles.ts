/**
 * EDDI's realm roles and which Manager screens each one can use.
 *
 * The backend is the authority — every REST resource carries `@RolesAllowed`
 * and refuses what a role may not do. This table only keeps the Manager from
 * OFFERING what the backend will refuse: an `eddi-editor` used to see the Logs,
 * Audit and Vault quick actions and their sidebar entries, and a dashboard that
 * fired three admin-only requests on every load and reported the 403s as
 * "Vault unavailable" / "Coordinator —".
 *
 * Kept in step with the `@RolesAllowed` on the resource each screen calls
 * (`IRestLogAdmin`, `IRestCoordinatorAdmin`, `IRestAuditStore`,
 * `IRestSecretStore`, `IRestConnectionStore` admin CRUD, `IRestTenantQuota`,
 * `IRestOrphanAdmin`, `IRestGdprAdmin`, `IRestUserMemoryStore` /
 * `IRestPropertiesStore`).
 */

export const ROLE_ADMIN = "eddi-admin";
export const ROLE_EDITOR = "eddi-editor";
export const ROLE_USER = "eddi-user";
export const ROLE_VIEWER = "eddi-viewer";
export const ROLE_APPROVER = "eddi-approver";

/** Every role EDDI's resources name. */
export const EDDI_ROLES: readonly string[] = [ROLE_ADMIN, ROLE_EDITOR, ROLE_USER, ROLE_VIEWER, ROLE_APPROVER];

/**
 * Screens whose backend accepts only some roles, by path prefix. A path not
 * listed is open to everyone who can use the Manager at all.
 */
const SCREEN_ROLES: ReadonlyArray<readonly [prefix: string, roles: readonly string[]]> = [
  ["/manage/logs", [ROLE_ADMIN]],
  ["/manage/coordinator", [ROLE_ADMIN]],
  ["/manage/audit", [ROLE_ADMIN]],
  ["/manage/secrets", [ROLE_ADMIN]],
  // `IRestConnectionStore` is eddi-admin, but its descriptors and reads also
  // admit eddi-editor — an editor can open the list and a connection (read-only;
  // create, update and delete stay admin).
  ["/manage/connections", [ROLE_ADMIN, ROLE_EDITOR]],
  ["/manage/quotas", [ROLE_ADMIN]],
  ["/manage/orphans", [ROLE_ADMIN]],
  ["/manage/gdpr", [ROLE_ADMIN]],
  ["/manage/userdata", [ROLE_ADMIN, ROLE_USER]],
];

/** The roles that may use the screen at `path`, or `null` when any role may. */
export function rolesForScreen(path: string): readonly string[] | null {
  for (const [prefix, roles] of SCREEN_ROLES) {
    if (path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`)) {
      return roles;
    }
  }
  return null;
}

/**
 * Whether the token says anything about EDDI roles at all.
 *
 * A deployment can map roles from another claim (`quarkus.oidc.roles.role-claim-path`),
 * and then the Manager's `realm_access.roles` holds none of them. Hiding every
 * admin screen from a real admin because the Manager looked in the wrong claim
 * would be worse than the noise this table removes — so with no EDDI role in
 * sight the Manager shows everything, as it did before, and lets the backend
 * answer.
 */
export function rolesAreKnown(roles: readonly string[]): boolean {
  return roles.some((r) => EDDI_ROLES.includes(r));
}

/**
 * Whether a user with `roles` may use something that requires one of `required`.
 * `authDisabled` (no OIDC): everything. Unknown roles: everything (see `rolesAreKnown`).
 */
export function mayUse(
  required: readonly string[] | null,
  roles: readonly string[],
  authDisabled: boolean,
): boolean {
  if (!required || authDisabled || !rolesAreKnown(roles)) return true;
  return required.some((r) => roles.includes(r));
}
