/**
 * EDDI's realm roles and what each one can do in the Manager.
 *
 * The backend is the authority — every REST resource carries `@RolesAllowed`
 * and refuses what a role may not do. This table only keeps the Manager from
 * OFFERING what the backend will refuse: an `eddi-editor` used to see the Logs,
 * Audit and Vault quick actions and their sidebar entries, and a dashboard that
 * fired three admin-only requests on every load and reported the 403s as
 * "Vault unavailable" / "Coordinator —"; an `eddi-viewer` met "You don't have
 * permission" under every create button.
 */

export const ROLE_ADMIN = "eddi-admin";
export const ROLE_EDITOR = "eddi-editor";
export const ROLE_USER = "eddi-user";
export const ROLE_VIEWER = "eddi-viewer";
export const ROLE_APPROVER = "eddi-approver";

/** Every role EDDI's resources name. */
export const EDDI_ROLES: readonly string[] = [ROLE_ADMIN, ROLE_EDITOR, ROLE_USER, ROLE_VIEWER, ROLE_APPROVER];

/**
 * The permission map: what each capability needs, read from the backend's
 * `@RolesAllowed` (EDDI has no role hierarchy, so every endpoint lists every
 * role it admits). This is the ONE place the Manager decides what a role may
 * do. The route guard, the sidebar, the command palette, the dashboard and the
 * in-page buttons all read it (`useCan`, `useMayOpenScreen`); a page that needs
 * a new capability adds a line here instead of testing a role name.
 *
 * Derivation (each interface is under `src/main/java`):
 *
 * - admin + editor: `dashboard.read`, `agents.*`, `workflows.*`, `resources.*`,
 *   `variables.manage`, `groups.*`, `channels.manage`, `triggers.manage`,
 *   `schedules.manage`, `capabilities.read`, `sync.run`, `chat.use`,
 *   `operator.use`, `conversations.monitor`, `alerts.manage`, `feedback.read`,
 *   `modelPrices.read`, `workspaces.read`, `connections.read` — from
 *   `IRestAgentStore`, `IRestDocumentDescriptorStore`, `IRestWorkflowStore`,
 *   the extension stores, `IRestAgentGroupStore`, `IRestAgentAdministration`,
 *   `IRestDeploymentStore`, `IRestChannelIntegrationStore`,
 *   `IRestAgentTriggerStore`, `IRestScheduleStore`, `IRestCapabilityRegistry`,
 *   `IRestExportService` / `IRestImportService`, `IRestGlobalVariableStore`,
 *   `IRestAlertRules` / `IRestAlertEvents`, `IRestConversationRatingAdmin`,
 *   `IRestModelPriceCatalog` (reads), `IRestWorkspaces` (class gate),
 *   `IRestConnectionStore` (descriptor and read methods),
 *   `IRestConversationStore.getActiveConversations`.
 * - admin only: `agents.signingKey` (`IRestAgentStore` signing-key methods),
 *   `setup.guided` (`IRestAgentSetup` — it writes API keys to the vault; an
 *   editor creates the same resources one by one, which is Quick Create),
 *   `connections.manage` (`IRestConnectionStore`), `secrets.manage`
 *   (`IRestSecretStore`), `logs.read` (`IRestLogAdmin`), `audit.read`
 *   (`IRestAuditStore`), `quotas.manage` (`IRestTenantQuota`),
 *   `orphans.manage` (`IRestOrphanAdmin`), `gdpr.admin` (`IRestGdprAdmin`),
 *   `branding.manage` (`IRestBrandingAdmin`), `conversations.purge`
 *   (`IRestConversationStore` purge and end-inactive), `modelPrices.manage`
 *   (`IRestModelPriceCatalog` replace), `workspaces.manage` (`IRestWorkspaces`
 *   settings), `teamBudgets.transfer` (`IRestTeamBudgets`),
 *   `hitl.analyticsRebuild` (`IRestApprovalAnalytics.rebuild`), `cluster.act`
 *   (`IRestClusterAdmin` recovery actions and the dead-letter listing).
 * - `cluster.read`: admin + viewer — `IRestClusterAdmin` overview, leases,
 *   activity, diagnose and dead-letter counts.
 * - `oversight.read`: admin, editor, approver — `IRestApprovalAnalytics`,
 *   `IRestAuditSamples`, `IRestAutonomy`.
 * - `approvals.decide`: admin, editor, user, approver — `IRestAgentEngine`'s
 *   approval methods.
 * - `userdata.own`: admin + user — `IRestUserMemoryStore`, `IRestPropertiesStore`;
 *   `userdata.conversations`: admin only — `IRestUserConversationStore`.
 *
 * So `eddi-viewer` opens ONE Manager screen: the cluster console. It also reads
 * documentation, agent usage, workspace info and team budgets, which have no
 * screen of their own.
 *
 * Roles come from `realm_access.roles`, the only claim the backend reads
 * (`quarkus.oidc.roles.role-claim-path`); `resource_access` is ignored there,
 * so it is ignored here (`realmRolesOf`).
 */
export const CAPABILITY_ROLES = {
  "dashboard.read": [ROLE_ADMIN, ROLE_EDITOR],
  "agents.read": [ROLE_ADMIN, ROLE_EDITOR],
  "agents.write": [ROLE_ADMIN, ROLE_EDITOR],
  "agents.deploy": [ROLE_ADMIN, ROLE_EDITOR],
  "agents.signingKey": [ROLE_ADMIN],
  "setup.guided": [ROLE_ADMIN],
  "workflows.read": [ROLE_ADMIN, ROLE_EDITOR],
  "workflows.write": [ROLE_ADMIN, ROLE_EDITOR],
  "resources.read": [ROLE_ADMIN, ROLE_EDITOR],
  "resources.write": [ROLE_ADMIN, ROLE_EDITOR],
  "variables.manage": [ROLE_ADMIN, ROLE_EDITOR],
  "groups.read": [ROLE_ADMIN, ROLE_EDITOR],
  "groups.write": [ROLE_ADMIN, ROLE_EDITOR],
  "channels.manage": [ROLE_ADMIN, ROLE_EDITOR],
  "triggers.manage": [ROLE_ADMIN, ROLE_EDITOR],
  "schedules.manage": [ROLE_ADMIN, ROLE_EDITOR],
  "capabilities.read": [ROLE_ADMIN, ROLE_EDITOR],
  "sync.run": [ROLE_ADMIN, ROLE_EDITOR],
  "chat.use": [ROLE_ADMIN, ROLE_EDITOR],
  "operator.use": [ROLE_ADMIN, ROLE_EDITOR],
  "conversations.monitor": [ROLE_ADMIN, ROLE_EDITOR],
  "conversations.purge": [ROLE_ADMIN],
  "alerts.manage": [ROLE_ADMIN, ROLE_EDITOR],
  "feedback.read": [ROLE_ADMIN, ROLE_EDITOR],
  "oversight.read": [ROLE_ADMIN, ROLE_EDITOR, ROLE_APPROVER],
  "hitl.analyticsRebuild": [ROLE_ADMIN],
  "approvals.decide": [ROLE_ADMIN, ROLE_EDITOR, ROLE_USER, ROLE_APPROVER],
  "connections.read": [ROLE_ADMIN, ROLE_EDITOR],
  "connections.manage": [ROLE_ADMIN],
  "modelPrices.read": [ROLE_ADMIN, ROLE_EDITOR],
  "modelPrices.manage": [ROLE_ADMIN],
  "workspaces.read": [ROLE_ADMIN, ROLE_EDITOR],
  "workspaces.manage": [ROLE_ADMIN],
  "teamBudgets.transfer": [ROLE_ADMIN],
  "secrets.manage": [ROLE_ADMIN],
  "logs.read": [ROLE_ADMIN],
  "audit.read": [ROLE_ADMIN],
  "quotas.manage": [ROLE_ADMIN],
  "orphans.manage": [ROLE_ADMIN],
  "gdpr.admin": [ROLE_ADMIN],
  "branding.manage": [ROLE_ADMIN],
  "cluster.read": [ROLE_ADMIN, ROLE_VIEWER],
  "cluster.act": [ROLE_ADMIN],
  "userdata.own": [ROLE_ADMIN, ROLE_USER],
  // `IRestUserConversationStore` is class-level admin only (the other two tabs admit eddi-user).
  "userdata.conversations": [ROLE_ADMIN],
} as const satisfies Record<string, readonly string[]>;

export type Capability = keyof typeof CAPABILITY_ROLES;

/** The roles that hold `capability`. */
export function rolesForCapability(capability: Capability): readonly string[] {
  return CAPABILITY_ROLES[capability];
}

/**
 * The capability a Manager screen needs, by path prefix; the FIRST match wins,
 * so a more specific path sits above its parent. A path not listed is open to
 * every role that can reach the Manager at all.
 */
// route-integrity:ignore-start (permission-gate prefixes, not navigation targets)
const SCREENS: ReadonlyArray<{ path: string; capability: Capability; exact?: boolean }> = [
  { path: "/manage", capability: "dashboard.read", exact: true },
  { path: "/manage/agents/wizard", capability: "setup.guided" },
  { path: "/manage/studio", capability: "agents.write" },
  { path: "/manage/agents", capability: "agents.read" },
  { path: "/manage/agentview", capability: "agents.read" },
  { path: "/manage/workflows", capability: "workflows.read" },
  { path: "/manage/workflowview", capability: "workflows.read" },
  { path: "/manage/resources", capability: "resources.read" },
  { path: "/manage/groups", capability: "groups.read" },
  { path: "/manage/channels", capability: "channels.manage" },
  { path: "/manage/triggers", capability: "triggers.manage" },
  { path: "/manage/schedules", capability: "schedules.manage" },
  { path: "/manage/capabilities", capability: "capabilities.read" },
  { path: "/manage/sync", capability: "sync.run" },
  { path: "/manage/variables", capability: "variables.manage" },
  { path: "/manage/chat", capability: "chat.use" },
  { path: "/manage/operator", capability: "operator.use" },
  { path: "/manage/conversations/monitoring", capability: "conversations.monitor" },
  { path: "/manage/approvals", capability: "approvals.decide" },
  { path: "/manage/oversight", capability: "oversight.read" },
  { path: "/manage/alerts", capability: "alerts.manage" },
  { path: "/manage/feedback", capability: "feedback.read" },
  // `IRestConnectionStore` is eddi-admin, but its descriptors and reads also
  // admit eddi-editor — an editor can open the list and a connection (read-only;
  // create, update and delete stay admin: `connections.manage`).
  { path: "/manage/connections", capability: "connections.read" },
  { path: "/manage/model-prices", capability: "modelPrices.read" },
  { path: "/manage/workspaces", capability: "workspaces.read" },
  { path: "/manage/secrets", capability: "secrets.manage" },
  { path: "/manage/logs", capability: "logs.read" },
  { path: "/manage/audit", capability: "audit.read" },
  // The cluster console admits the read-only viewer too; its dead-letter
  // listing and recovery actions are `cluster.act`, which the page checks.
  { path: "/manage/coordinator", capability: "cluster.read" },
  { path: "/manage/quotas", capability: "quotas.manage" },
  { path: "/manage/orphans", capability: "orphans.manage" },
  { path: "/manage/gdpr", capability: "gdpr.admin" },
  // `IRestBrandingAdmin`: eddi-admin only — a brand is deployment identity.
  { path: "/manage/branding", capability: "branding.manage" },
  { path: "/manage/userdata", capability: "userdata.own" },
];
// route-integrity:ignore-end

/** The capability the screen at `path` needs, or `null` when any role may open it. */
export function capabilityForScreen(path: string): Capability | null {
  // React Router matches case-insensitively and tolerates repeated slashes, so
  // /manage/Logs and /manage//logs must find the same entry as /manage/logs.
  const bare = (path.split(/[?#]/, 1)[0] ?? path).toLowerCase().replace(/\/{2,}/g, "/");
  const trimmed = bare.length > 1 ? bare.replace(/\/+$/, "") : bare;
  const hit = SCREENS.find((s) =>
    s.exact ? trimmed === s.path : trimmed === s.path || trimmed.startsWith(`${s.path}/`),
  );
  return hit?.capability ?? null;
}

/** The roles that may use the screen at `path`, or `null` when any role may. */
export function rolesForScreen(path: string): readonly string[] | null {
  const capability = capabilityForScreen(path);
  return capability ? CAPABILITY_ROLES[capability] : null;
}

/**
 * The realm roles in a parsed access token, from `realm_access.roles` — the one
 * claim the backend reads. `resource_access` is not an EDDI role as far as the
 * server is concerned, so it is not one here either.
 */
export function realmRolesOf(tokenParsed: unknown): string[] {
  const roles = (tokenParsed as { realm_access?: { roles?: unknown } } | undefined)?.realm_access?.roles;
  return Array.isArray(roles) ? roles.filter((r): r is string => typeof r === "string") : [];
}

/** The EDDI roles among `roles`, most privileged first. */
export function eddiRolesOf(roles: readonly string[]): string[] {
  return EDDI_ROLES.filter((r) => roles.includes(r));
}

/**
 * Where a signed-in person lands, by role: the one place their role is for.
 *
 * Every realm user also holds `eddi-user` (the realm's default composite), so
 * a viewer's real token is `[eddi-viewer, eddi-user, …]` and an approver's
 * `[eddi-approver, eddi-user, …]`. The explicit role wins over that default:
 * a viewer lands on the cluster console, an approver on the approvals inbox —
 * both can still open Workforce as a user from the no-access page and the
 * landing chooser. Authors (admin, editor) land on the dashboard.
 */
export function homePathForRoles(roles: readonly string[]): string {
  if (roles.includes(ROLE_ADMIN) || roles.includes(ROLE_EDITOR)) return "/manage";
  if (roles.includes(ROLE_VIEWER)) return "/manage/coordinator";
  if (roles.includes(ROLE_APPROVER)) return "/manage/approvals";
  if (roles.includes(ROLE_USER)) return "/workforce";
  return "/manage";
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

/** Whether a user with `roles` holds `capability` (see `mayUse` for the degradation rules). */
export function mayDo(capability: Capability, roles: readonly string[], authDisabled: boolean): boolean {
  return mayUse(CAPABILITY_ROLES[capability], roles, authDisabled);
}
