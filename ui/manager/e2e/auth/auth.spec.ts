import { expect, test } from "@playwright/test";
import {
  API_BASE,
  KEYCLOAK_BASE,
  REALM,
  SPA_CLIENT_ID,
  USERS,
  authHeaders,
  decodeClaims,
  realmRoles,
  tokenFor,
  waitForBackend,
} from "./auth-helpers";

/**
 * The authenticated path, against a backend running with OIDC ENFORCED.
 *
 * Every other backend-facing tier boots EDDI with
 * `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`, which sets
 * `authorization.enabled=false` and makes every `@RolesAllowed` a no-op — so
 * until this tier existed, nothing in the suite had ever sent a token or seen a
 * role checked. The first run of it found that the shipped realm's
 * administrator was refused every endpoint.
 *
 * Deliberately API-level rather than driving Keycloak's login form: the
 * authorization-code flow adds a browser redirect dance whose failures are
 * mostly its own, while these assertions pin the contract that actually breaks —
 * which token is accepted, and which role opens which door.
 */
test.describe("Authentication and authorization — Keycloak", () => {
  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test("the SPA is told to use Keycloak, with the client id the backend hardcodes", async ({
    request,
  }) => {
    // Served by Quarkus, not Vite, and it is how the SPA learns where to log in.
    const res = await request.get(`${API_BASE}/manage/__auth_config__.js`);
    expect(res.ok()).toBe(true);
    const body = await res.text();

    expect(body).toContain('method:"keycloak"');
    expect(body).toContain(`realm:"${REALM}"`);
    // A realm whose client id differs from this is a realm nobody can log in
    // through — the failure mode of the Manager's old committed realm, which
    // named `eddi-manager` while the backend asked for `eddi-frontend`.
    expect(body).toContain(`clientId:"${SPA_CLIENT_ID}"`);
    // The browser-reachable Keycloak, not the compose-network hostname.
    expect(body).toContain(`url:"${KEYCLOAK_BASE}"`);
  });

  test("an unauthenticated API call is rejected", async ({ request }) => {
    const res = await request.get(`${API_BASE}/agentstore/agents/descriptors`);
    expect(
      res.status(),
      "OIDC is enforced in this tier, so an anonymous call must be 401 — a 200 here means"
        + " authorization is switched off and every other assertion below is vacuous",
    ).toBe(401);
  });

  test("a garbage bearer token is rejected", async ({ request }) => {
    const res = await request.get(`${API_BASE}/agentstore/agents/descriptors`, {
      headers: authHeaders("not.a.jwt"),
    });
    expect(res.status()).toBe(401);
  });

  test("the administrator's token carries realm roles AND a group", async ({ request }) => {
    const claims = decodeClaims(await tokenFor(request, "admin"));

    expect(
      (claims.realm_access as { roles?: string[] } | undefined)?.roles ?? [],
      "the realm no longer grants eddi its roles",
    ).toEqual(expect.arrayContaining([...USERS.admin.roles]));

    // The reason this fixture is the admin. Quarkus OIDC reads roles from
    // `groups` unless told otherwise, so a token carrying both is the case that
    // regressed — and the one the next test proves is now handled.
    expect(
      claims.groups ?? [],
      "eddi is no longer in a Keycloak group, so this tier stopped covering the"
        + " groups-claim collision that quarkus.oidc.roles.role-claim-path exists to fix."
        + " Put it back in a group, or move the coverage somewhere it holds.",
    ).not.toEqual([]);
  });

  test("the administrator is allowed through, despite belonging to a group", async ({
    request,
  }) => {
    const token = await tokenFor(request, "admin");

    // eddi-admin + eddi-editor.
    const descriptors = await request.get(`${API_BASE}/agentstore/agents/descriptors`, {
      headers: authHeaders(token),
    });
    expect(
      descriptors.status(),
      "403 here is the groups-claim collision: the token holds eddi-admin, but Quarkus read"
        + " roles from the `groups` claim EDDI's workspaces own. Check"
        + " quarkus.oidc.roles.role-claim-path in application.properties.",
    ).toBe(200);
    expect(Array.isArray(await descriptors.json())).toBe(true);

    // eddi-admin only.
    const orphans = await request.get(`${API_BASE}/administration/orphans`, {
      headers: authHeaders(token),
    });
    expect(orphans.status()).toBe(200);
  });

  for (const fixture of ["user", "viewer"] as const) {
    test(`${fixture} (${USERS[fixture].roles.join(", ")}) is authenticated but refused`, async ({
      request,
    }) => {
      const token = await tokenFor(request, fixture);

      // A real identity carrying exactly the role it should — so a 403 below is
      // authorization refusing a known role, not a token being rejected. (The
      // realm's client scopes do not include `profile`, so the token has no
      // preferred_username to assert on; the roles are the contract anyway.)
      expect(realmRoles(token)).toEqual(
        expect.arrayContaining([...USERS[fixture].roles]),
      );

      for (const path of ["/agentstore/agents/descriptors", "/administration/orphans"]) {
        expect(
          (await request.get(`${API_BASE}${path}`, { headers: authHeaders(token) })).status(),
          `${fixture} must not reach ${path}`,
        ).toBe(403);
      }
    });
  }
});
