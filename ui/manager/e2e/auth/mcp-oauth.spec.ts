import { createHash, randomBytes } from "node:crypto";
import { type APIRequestContext, expect, test } from "@playwright/test";
import { API_BASE, KEYCLOAK_BASE, REALM, USERS, waitForBackend } from "./auth-helpers";

/**
 * The whole point of advertising `/mcp` as an OAuth protected resource: a client
 * obtains its own token through the browser flow and then calls MCP tools with it.
 *
 * The sibling tests cover the two ends — the discovery document is anonymous, and
 * `/mcp` without a token is 401. Neither proves the middle, and the middle is
 * where this feature can fail without anything going red: a client id nobody can
 * use, a token whose `aud` the backend refuses, a token that authenticates and is
 * then refused by every tool because the realm client carries no roles mapper.
 *
 * Driven through Keycloak's form POST rather than a browser: the authorization
 * code flow is a sequence of redirects whose failure modes are the ones worth
 * asserting, and a headless browser adds its own.
 */

/** The public client the realm ships for MCP clients. */
const MCP_CLIENT_ID = "eddi-mcp";

/**
 * A loopback callback, matching `eddi-mcp`'s redirect URIs. Nothing listens on
 * it — the flow stops at the 302 and reads the code out of `Location`, which is
 * what a real client's local listener would do.
 */
const REDIRECT_URI = "http://localhost:9876/callback";

const base64url = (input: Buffer) =>
  input.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** RFC 7636 S256: `eddi-mcp` requires PKCE, so a verifier is not optional. */
function pkcePair() {
  const verifier = base64url(randomBytes(32));
  return { verifier, challenge: base64url(createHash("sha256").update(verifier).digest()) };
}

/** Keycloak renders the credentials form with the next URL in its `action`. */
function loginFormAction(html: string): string {
  const action = (/<form[^>]+id="kc-form-login"[^>]*action="([^"]+)"/i.exec(html)
    ?? /<form[^>]+action="([^"]+)"[^>]*id="kc-form-login"/i.exec(html))?.[1];
  expect(action, "Keycloak did not render its login form — the authorize request was rejected")
    .toBeTruthy();
  return (action as string).replace(/&amp;/g, "&");
}

/** Runs authorization-code + PKCE end to end and returns the access token. */
async function tokenViaAuthorizationCode(request: APIRequestContext): Promise<string> {
  const { verifier, challenge } = pkcePair();
  const authorize = new URL(`${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/auth`);
  authorize.search = new URLSearchParams({
    client_id: MCP_CLIENT_ID,
    response_type: "code",
    redirect_uri: REDIRECT_URI,
    scope: "openid",
    state: base64url(randomBytes(8)),
    code_challenge: challenge,
    code_challenge_method: "S256",
  }).toString();

  const form = await request.get(authorize.toString());
  expect(
    form.status(),
    `Keycloak refused the authorize request for ${MCP_CLIENT_ID}. A 400 here usually means the`
      + ` redirect URI is not on the client, or the client does not exist in this realm.`,
  ).toBe(200);

  const submitted = await request.post(loginFormAction(await form.text()), {
    form: { username: USERS.admin.username, password: USERS.admin.password, credentialId: "" },
    maxRedirects: 0,
  });
  expect(
    submitted.status(),
    "the login POST did not redirect — check the seeded administrator's password",
  ).toBe(302);

  const location = submitted.headers()["location"] ?? "";
  expect(location, `no redirect target after login; got: ${location}`).toContain(REDIRECT_URI);
  const code = new URL(location).searchParams.get("code");
  expect(code, `no authorization code in the redirect: ${location}`).toBeTruthy();

  const exchanged = await request.post(
    `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token`,
    {
      form: {
        grant_type: "authorization_code",
        client_id: MCP_CLIENT_ID,
        code: code as string,
        redirect_uri: REDIRECT_URI,
        code_verifier: verifier,
      },
    },
  );
  expect(
    exchanged.status(),
    `the token exchange failed: ${await exchanged.text()}`,
  ).toBe(200);

  const token = ((await exchanged.json()) as { access_token?: string }).access_token;
  expect(token, "the token response carried no access_token").toBeTruthy();
  return token as string;
}

/** Claims of a JWT, unverified — the backend is what verifies it. */
function claims(token: string): Record<string, unknown> {
  const payload = token.split(".")[1] as string;
  const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(normalized.length + ((4 - (normalized.length % 4)) % 4), "=");
  return JSON.parse(Buffer.from(padded, "base64").toString("utf8")) as Record<string, unknown>;
}

/**
 * The MCP endpoint answers either JSON or an SSE frame depending on what the
 * client accepts; both carry the same JSON-RPC envelope.
 */
function jsonRpc(body: string): Record<string, unknown> {
  const data = body.startsWith("event:") || body.startsWith("data:")
    ? body.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("")
    : body;
  return JSON.parse(data) as Record<string, unknown>;
}

test.describe("MCP over OAuth — a client signs itself in and calls a tool", () => {
  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test("authorization code + PKCE yields a token EDDI accepts on /mcp", async ({ request }) => {
    const token = await tokenViaAuthorizationCode(request);
    const payload = claims(token);

    // What #805 validates. Without the audience mapper on eddi-mcp this token
    // would authenticate everywhere else and be refused here.
    //
    // `aud` is `string | string[]` (RFC 7519 §4.1.3) and Keycloak emits the bare
    // string when there is exactly one audience, which is this token's shape.
    const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    expect(
      audiences,
      "the token carries no eddi-backend audience, so quarkus.oidc.token.audience will refuse it",
    ).toContain("eddi-backend");

    // And the roles, which is the failure a self-registered client would hit.
    const roles = (payload.realm_access as { roles?: string[] } | undefined)?.roles ?? [];
    expect(
      roles,
      "the token carries no realm roles — eddi-mcp is missing its realm-roles protocol mapper,"
        + " which authenticates fine and then fails every MCP tool with 'requires role'",
    ).toEqual(expect.arrayContaining(["eddi-viewer"]));

    const initialize = await request.post(`${API_BASE}/mcp`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json, text/event-stream",
        "Content-Type": "application/json",
      },
      data: {
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: {
          protocolVersion: "2025-06-18",
          capabilities: {},
          clientInfo: { name: "eddi-auth-e2e", version: "1.0.0" },
        },
      },
    });
    expect(
      initialize.status(),
      `/mcp refused a token obtained through its own advertised flow: ${await initialize.text()}`,
    ).toBe(200);

    const session = initialize.headers()["mcp-session-id"];
    const result = jsonRpc(await initialize.text()).result as { protocolVersion?: string };
    expect(result?.protocolVersion, "initialize returned no protocol version").toBeTruthy();

    // The half that a 200 on initialize does not prove: a tool actually runs for
    // this identity. list_agents is eddi-viewer-gated, which is the role the
    // seeded administrator holds only because the realm grants it deliberately.
    const call = await request.post(`${API_BASE}/mcp`, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/json, text/event-stream",
        "Content-Type": "application/json",
        ...(session ? { "Mcp-Session-Id": session } : {}),
      },
      data: { jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "list_agents", arguments: {} } },
    });
    expect(call.status(), `tools/call was refused: ${await call.text()}`).toBe(200);

    const body = jsonRpc(await call.text());
    expect(
      JSON.stringify(body),
      "list_agents answered with an error — a 'requires role' here means the token's roles did"
        + " not survive, and an auth error means /mcp did not accept this session",
    ).not.toMatch(/requires role|Unauthorized|Forbidden/i);
    expect(body.result, `list_agents returned no result: ${JSON.stringify(body)}`).toBeTruthy();
  });
});
