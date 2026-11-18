import { describe, it, expect } from "vitest";
import {
  CAPABILITY_ROLES,
  capabilityForScreen,
  mayDo,
  mayUse,
  realmRolesOf,
  rolesAreKnown,
  rolesForScreen,
  ROLE_ADMIN,
  ROLE_APPROVER,
  ROLE_EDITOR,
  ROLE_USER,
  ROLE_VIEWER,
  type Capability,
} from "@eddi/app-kit/lib/roles";

/**
 * `SCREEN_ROLES` mirrors the backend's `@RolesAllowed`. Each expectation below
 * names the resource it was read from, so a change on either side is checked
 * against the other.
 */
describe("rolesForScreen", () => {
  it.each([
    // IRestLogAdmin, IRestCoordinatorAdmin, IRestAuditStore, IRestSecretStore,
    // IRestTenantQuota, IRestOrphanAdmin, IRestGdprAdmin: eddi-admin only.
    ["/manage/logs", [ROLE_ADMIN]],
    // IRestClusterAdmin: the overview, leases, activity and diagnosis also admit the viewer.
    ["/manage/coordinator", [ROLE_ADMIN, ROLE_VIEWER]],
    ["/manage/audit", [ROLE_ADMIN]],
    ["/manage/secrets", [ROLE_ADMIN]],
    ["/manage/quotas", [ROLE_ADMIN]],
    ["/manage/orphans", [ROLE_ADMIN]],
    ["/manage/gdpr", [ROLE_ADMIN]],
    // IRestBrandingAdmin: eddi-admin only.
    ["/manage/branding", [ROLE_ADMIN]],
    // IRestConnectionStore: descriptors and reads admit eddi-editor too.
    ["/manage/connections", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/connections/abc123", [ROLE_ADMIN, ROLE_EDITOR]],
    // IRestUserMemoryStore / IRestPropertiesStore: eddi-admin and eddi-user.
    ["/manage/userdata", [ROLE_ADMIN, ROLE_USER]],
    // IRestConversationRatingAdmin: eddi-admin and eddi-editor, no viewer.
    ["/manage/feedback", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/feedback?agentId=a1", [ROLE_ADMIN, ROLE_EDITOR]],
    // The authoring stores: admin and editor, nobody else (I3).
    ["/manage", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/agents", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/agentview/a1", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/groups/g1/workspace", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/studio/a1", [ROLE_ADMIN, ROLE_EDITOR]],
    // IRestAgentSetup: the guided wizard ends in an admin-only endpoint (OI3).
    ["/manage/agents/wizard", [ROLE_ADMIN]],
    ["/manage/approvals", [ROLE_ADMIN, ROLE_EDITOR, ROLE_USER, ROLE_APPROVER]],
  ])("%s needs one of %j", (path, roles) => {
    expect(rolesForScreen(path)).toEqual(roles);
  });

  it("matches whole path segments, not a shared prefix", () => {
    expect(rolesForScreen("/manage/logs?level=ERROR")).toEqual([ROLE_ADMIN]);
    expect(rolesForScreen("/manage/logsearch")).toBeNull();
  });

  it("leaves the per-user and shared screens open to any role", () => {
    for (const path of ["/manage/linked-accounts", "/manage/conversations", "/manage/conversationview/c1", "/manage/updates"]) {
      expect(rolesForScreen(path)).toBeNull();
    }
  });

  it("matches the dashboard only exactly, not every /manage path", () => {
    expect(capabilityForScreen("/manage/")).toBe("dashboard.read");
    expect(capabilityForScreen("/manage/linked-accounts")).toBeNull();
  });

  it("puts the wizard above the agents list so the longer path wins", () => {
    expect(capabilityForScreen("/manage/agents/wizard")).toBe("setup.guided");
    expect(capabilityForScreen("/manage/agents")).toBe("agents.read");
  });
});

describe("mayUse", () => {
  it("lets an editor open connections but not the vault", () => {
    const editor = [ROLE_EDITOR, "default-roles-eddi"];
    expect(mayUse(rolesForScreen("/manage/connections"), editor, false)).toBe(true);
    expect(mayUse(rolesForScreen("/manage/secrets"), editor, false)).toBe(false);
  });

  it("offers everything with auth off, or when the token carries no EDDI role", () => {
    expect(mayUse([ROLE_ADMIN], [], true)).toBe(true);
    expect(rolesAreKnown(["offline_access", "uma_authorization"])).toBe(false);
    expect(mayUse([ROLE_ADMIN], ["offline_access"], false)).toBe(true);
  });

  it("refuses only when the token positively shows other EDDI roles", () => {
    expect(mayUse([ROLE_ADMIN], [ROLE_USER], false)).toBe(false);
    expect(mayUse([ROLE_ADMIN], [ROLE_ADMIN], false)).toBe(true);
    expect(mayUse(null, [ROLE_USER], false)).toBe(true);
  });
});

describe("the permission map, one role at a time", () => {
  const ALL = Object.keys(CAPABILITY_ROLES) as Capability[];
  const allowed = (roles: string[]) => ALL.filter((c) => mayDo(c, roles, false));

  it("admin holds every capability", () => {
    expect(allowed([ROLE_ADMIN])).toEqual(ALL);
  });

  it("editor holds the authoring capabilities, not the admin-only ones", () => {
    const got = allowed([ROLE_EDITOR]);
    for (const c of ["agents.write", "groups.write", "connections.read", "feedback.read", "conversations.monitor"] as const) {
      expect(got).toContain(c);
    }
    for (const c of ["setup.guided", "connections.manage", "secrets.manage", "logs.read", "gdpr.admin", "conversations.purge", "cluster.read"] as const) {
      expect(got).not.toContain(c);
    }
  });

  it("viewer holds exactly one Manager capability: the cluster console, read-only", () => {
    expect(allowed([ROLE_VIEWER])).toEqual(["cluster.read"]);
  });

  it("user holds approvals and their own data, nothing that authors", () => {
    expect(allowed([ROLE_USER])).toEqual(["approvals.decide", "userdata.own"]);
  });

  it("approver decides approvals and reads oversight", () => {
    expect(allowed([ROLE_APPROVER])).toEqual(["oversight.read", "approvals.decide"]);
  });

  it("grants everything with auth off, whatever roles are present", () => {
    expect(ALL.every((c) => mayDo(c, [], true))).toBe(true);
    expect(ALL.every((c) => mayDo(c, [ROLE_VIEWER], true))).toBe(true);
  });

  it("grants everything for a token that carries no EDDI role", () => {
    expect(ALL.every((c) => mayDo(c, ["default-roles-eddi"], false))).toBe(true);
  });
});

describe("realmRolesOf", () => {
  it("reads realm_access.roles, the claim the backend reads", () => {
    expect(realmRolesOf({ realm_access: { roles: [ROLE_EDITOR, "offline_access"] } })).toEqual([ROLE_EDITOR, "offline_access"]);
  });

  it("ignores resource_access and malformed claims", () => {
    expect(realmRolesOf({ resource_access: { "eddi-backend": { roles: [ROLE_ADMIN] } } })).toEqual([]);
    expect(realmRolesOf({ realm_access: { roles: "eddi-admin" } })).toEqual([]);
    expect(realmRolesOf(undefined)).toEqual([]);
  });
});
