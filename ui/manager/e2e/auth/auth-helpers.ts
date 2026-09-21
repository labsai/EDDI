import { type APIRequestContext, expect } from "@playwright/test";

/**
 * The backend under test, and the Keycloak beside it.
 *
 * Both are published on loopback by `docker-compose.integration-keycloak.yml`.
 * Keycloak sits on 8180 rather than 8080 because that is the port
 * `application.properties` already names in `quarkus.oidc.auth-server-url`.
 */
export const API_BASE = `http://localhost:${Number(process.env.PORT) || 7070}`;
export const KEYCLOAK_BASE = process.env.KEYCLOAK_BASE ?? "http://localhost:8180";
export const REALM = "eddi";

/**
 * The public SPA client. The backend hardcodes this id into
 * `/manage/__auth_config__.js`, so a realm without it could not log anyone in —
 * which is exactly how the Manager's old committed realm was broken.
 */
export const SPA_CLIENT_ID = "eddi-frontend";

/**
 * The three fixtures the shipped realm seeds, with the roles each carries.
 *
 * `eddi` also belongs to the `engineering` Keycloak group, and that is the point
 * of including it: Quarkus OIDC reads roles from the `groups` claim by default,
 * so before `quarkus.oidc.roles.role-claim-path` was pinned to
 * `realm_access/roles` this user authenticated and was then refused everything.
 * A tier that only tested group-less users would have passed throughout.
 *
 * Its password is supplied by `scripts/make-test-realm.mjs`; the shipped realm
 * deliberately seeds no credential for it.
 */
export const USERS = {
  admin: {
    username: "eddi",
    password: "e2e-admin-password",
    // eddi-viewer alongside the other two: there is no role hierarchy, so an
    // administrator without it is refused every MCP read tool. Kept in step with
    // ROLE_FIXTURES in scripts/make-test-realm.mjs, which fails the run otherwise.
    roles: ["eddi-admin", "eddi-editor", "eddi-viewer"],
  },
  user: { username: "user", password: "user", roles: ["eddi-user"] },
  viewer: { username: "viewer", password: "viewer", roles: ["eddi-viewer"] },
} as const;

export type Fixture = keyof typeof USERS;

/** Exchange a fixture's password for an access token (direct access grant). */
export async function tokenFor(
  request: APIRequestContext,
  fixture: Fixture,
): Promise<string> {
  const { username, password } = USERS[fixture];
  const res = await request.post(
    `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token`,
    {
      form: {
        client_id: SPA_CLIENT_ID,
        grant_type: "password",
        username,
        password,
      },
    },
  );
  expect(
    res.ok(),
    `Keycloak refused a token for "${username}" (${res.status()}): ${await res.text()}`,
  ).toBe(true);

  const body = (await res.json()) as { access_token?: string };
  expect(body.access_token, `no access_token for "${username}"`).toBeTruthy();
  return body.access_token as string;
}

/** The claims inside an access token, without verifying its signature. */
export function decodeClaims(accessToken: string): Record<string, unknown> {
  const payload = accessToken.split(".")[1];
  if (!payload) throw new Error(`not a JWT: ${accessToken.slice(0, 16)}…`);
  const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), "=");
  return JSON.parse(Buffer.from(padded, "base64").toString("utf8")) as Record<string, unknown>;
}

/** The realm roles a token carries, which is what @RolesAllowed is graded against. */
export function realmRoles(accessToken: string): string[] {
  const claims = decodeClaims(accessToken) as { realm_access?: { roles?: string[] } };
  return claims.realm_access?.roles ?? [];
}

export function authHeaders(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

/** Poll liveness, exactly as the other backend-facing tiers do. */
export async function waitForBackend(request: APIRequestContext, timeoutMs = 60_000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await request.get(`${API_BASE}/q/health/live`, { timeout: 5000 });
      if (res.ok() && (await res.json()).status === "UP") return;
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`Backend did not become healthy within ${timeoutMs / 1000}s`);
}
