import { expect, test } from "@playwright/test";
import {
  API_BASE,
  KEYCLOAK_BASE,
  REALM,
  SPA_CLIENT_ID,
  USERS,
  type Fixture,
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

  // The realm once defined a single client scope, `openid`. Supplying any
  // clientScopes stops Keycloak creating its built-ins, so profile, email and
  // basic never existed: tokens authenticated and carried their roles, but had
  // no sub, preferred_username, name or email. The backend then resolved every
  // caller's principal to null, and the Manager's avatar read "?". Every test
  // above passed throughout, because none of them asked who the caller was.
  for (const fixture of Object.keys(USERS) as Fixture[]) {
    test(`${fixture}'s token says who they are, without asking for a scope`, async ({ request }) => {
      // tokenFor sends no `scope` parameter, like any direct-grant or CLI client.
      const claims = decodeClaims(await tokenFor(request, fixture));

      expect(claims.sub, "no `sub` — the realm's `basic` client scope is missing").toBeTruthy();
      expect(
        claims.preferred_username,
        "no preferred_username — the realm's `profile` client scope is missing, so the backend's"
          + " principal has no name",
      ).toBe(USERS[fixture].username);
      expect(claims.name, "no `name` — the Manager cannot say who is signed in").toBeTruthy();
      expect(claims.email, "no `email` — the realm's `email` client scope is missing").toBeTruthy();
      // Keycloak's userinfo endpoint refuses a token whose scope lacks openid, and
      // the backend calls it on every request (user-info-required=true).
      expect(String(claims.scope ?? "").split(" ")).toContain("openid");
    });
  }

  test("the backend resolves the caller's principal to their username", async ({ request }) => {
    const res = await request.get(`${API_BASE}/workspaces`, {
      headers: authHeaders(await tokenFor(request, "admin")),
    });
    expect(res.status()).toBe(200);
    const info = (await res.json()) as { principal?: string; defaultSpace?: string };
    expect(
      info.principal,
      "the backend authenticated the caller but could not name them. Ownership, attribution and"
        + " workspaces all key on this name; check the token's preferred_username",
    ).toBe(USERS.admin.username);
    expect(info.defaultSpace).toBe(`user:${USERS.admin.username}`);
  });

  test("a non-admin can open the conversation they started", async ({ request }) => {
    const admin = authHeaders(await tokenFor(request, "admin"));
    const user = authHeaders(await tokenFor(request, "user"));

    const workflow = await request.post(`${API_BASE}/workflowstore/workflows`, {
      headers: admin,
      data: { workflowSteps: [] },
    });
    expect(workflow.status()).toBe(201);
    const agent = await request.post(`${API_BASE}/agentstore/agents`, {
      headers: admin,
      data: { workflows: [workflow.headers()["location"]] },
    });
    expect(agent.status()).toBe(201);
    // Locations are `…/{id}?version=1`, as eddi:// or http URLs alike.
    const idOf = (location: string | undefined) => location!.split("/").pop()!.split("?")[0]!;
    const workflowId = idOf(workflow.headers()["location"]);
    const agentId = idOf(agent.headers()["location"]);

    let conversationId: string | undefined;
    try {
      const deploy = await request.post(
        `${API_BASE}/administration/production/deploy/${agentId}?version=1`,
        { headers: admin },
      );
      expect([200, 202]).toContain(deploy.status());
      await expect
        .poll(
          async () =>
            (
              await request.get(
                `${API_BASE}/administration/production/deploymentstatus/${agentId}?version=1`,
                { headers: admin },
              )
            ).text(),
          { timeout: 30_000 },
        )
        .toContain("READY");

      const start = await request.post(`${API_BASE}/agents/${agentId}/start`, { headers: user });
      expect(start.status()).toBe(201);
      conversationId = idOf(start.headers()["location"]);

      const stored = await request.get(
        `${API_BASE}/conversationstore/conversations/${conversationId}`,
        { headers: admin },
      );
      expect(((await stored.json()) as { userId?: string }).userId).toBe(USERS.user.username);

      // Without a principal name this was HTTP 500: OwnershipValidator compared the
      // stored owner against a null caller id.
      const own = await request.get(`${API_BASE}/agents/${conversationId}`, { headers: user });
      expect(own.status(), "the owner of a conversation must be able to read it").toBe(200);
    } finally {
      // Undeploying an agent with a live conversation answers 409 unless told to
      // end it, and Playwright does not throw on a 409: without the flag every
      // run left a deployed agent behind on the shared backend. Soft, so a
      // cleanup failure is reported without hiding the assertion that failed first.
      const undeploy = await request.post(
        `${API_BASE}/administration/production/undeploy/${agentId}?version=1&endAllActiveConversations=true`,
        { headers: admin },
      );
      expect.soft([200, 202], `undeploy answered ${undeploy.status()}`).toContain(undeploy.status());
      if (conversationId) {
        await request.delete(`${API_BASE}/conversationstore/conversations/${conversationId}`, {
          headers: admin,
        });
      }
      await request.delete(`${API_BASE}/agentstore/agents/${agentId}?version=1`, { headers: admin });
      await request.delete(`${API_BASE}/workflowstore/workflows/${workflowId}?version=1`, {
        headers: admin,
      });
    }
  });

  for (const fixture of ["user", "viewer"] as const) {
    test(`${fixture} (${USERS[fixture].roles.join(", ")}) is authenticated but refused`, async ({
      request,
    }) => {
      const token = await tokenFor(request, fixture);

      // A real identity carrying exactly the role it should — so a 403 below is
      // authorization refusing a known role, not a token being rejected.
      expect(decodeClaims(token).preferred_username).toBe(USERS[fixture].username);
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
