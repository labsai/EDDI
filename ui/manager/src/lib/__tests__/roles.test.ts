import { describe, it, expect } from "vitest";
import { mayUse, rolesAreKnown, rolesForScreen, ROLE_ADMIN, ROLE_EDITOR, ROLE_USER } from "@/lib/roles";

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
    ["/manage/coordinator", [ROLE_ADMIN]],
    ["/manage/audit", [ROLE_ADMIN]],
    ["/manage/secrets", [ROLE_ADMIN]],
    ["/manage/quotas", [ROLE_ADMIN]],
    ["/manage/orphans", [ROLE_ADMIN]],
    ["/manage/gdpr", [ROLE_ADMIN]],
    // IRestConnectionStore: descriptors and reads admit eddi-editor too.
    ["/manage/connections", [ROLE_ADMIN, ROLE_EDITOR]],
    ["/manage/connections/abc123", [ROLE_ADMIN, ROLE_EDITOR]],
    // IRestUserMemoryStore / IRestPropertiesStore: eddi-admin and eddi-user.
    ["/manage/userdata", [ROLE_ADMIN, ROLE_USER]],
  ])("%s needs one of %j", (path, roles) => {
    expect(rolesForScreen(path)).toEqual(roles);
  });

  it("matches whole path segments, not a shared prefix", () => {
    expect(rolesForScreen("/manage/logs?level=ERROR")).toEqual([ROLE_ADMIN]);
    expect(rolesForScreen("/manage/logsearch")).toBeNull();
  });

  it("leaves every other screen open to any role", () => {
    for (const path of ["/manage", "/manage/agents", "/manage/approvals", "/manage/linked-accounts"]) {
      expect(rolesForScreen(path)).toBeNull();
    }
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
