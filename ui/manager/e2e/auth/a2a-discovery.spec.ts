import { expect, test } from "@playwright/test";
import { API_BASE, KEYCLOAK_BASE, authHeaders, tokenFor, waitForBackend } from "./auth-helpers";

/**
 * Who can reach the A2A endpoints on a backend running with OIDC ENFORCED.
 *
 * Five of them carried `@PermitAll`, meaning "anonymous peers may call this".
 * None of them were named in a `quarkus.http.auth.permission.*` entry, and
 * Quarkus evaluates those path policies BEFORE declarative RBAC — so the
 * `/*` catch-all claimed all five and every credential-less A2A peer got a bare
 * 401 (bare because `quarkus.oidc.application-type=service` sends no login
 * redirect). The annotation said one thing and the deployment did another for
 * as long as the endpoints have existed.
 *
 * It survived because this is the only tier that enforces authentication. In
 * every other one `DisabledAuthController` switches the path policies off
 * wholesale, and a permitted path and a protected path answer identically.
 * `A2aEndpointPermissionsTest` now pins the same decision statically, without a
 * container; these are the status codes a real peer sees.
 */
test.describe("A2A discovery — anonymous reachability", () => {
  /** Ids created by `beforeAll`, torn down in `afterAll`. */
  let agentId: string;
  let workflowId: string;

  const idOf = (location: string | undefined) => location!.split("/").pop()!.split("?")[0]!;

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
    const admin = authHeaders(await tokenFor(request, "admin"));

    // An Agent Card is built straight from the stored configuration — no
    // deployment needed — but there has to BE an A2A-enabled agent, or both card
    // endpoints answer 404 and a 401 regression would hide behind an empty
    // instance.
    const workflow = await request.post(`${API_BASE}/workflowstore/workflows`, {
      headers: admin,
      data: { workflowSteps: [] },
    });
    expect(workflow.status()).toBe(201);
    workflowId = idOf(workflow.headers()["location"]);

    const agent = await request.post(`${API_BASE}/agentstore/agents`, {
      headers: admin,
      data: {
        workflows: [workflow.headers()["location"]],
        a2aEnabled: true,
        description: "A2A discovery fixture",
        a2aSkills: ["order-tracking"],
      },
    });
    expect(agent.status()).toBe(201);
    agentId = idOf(agent.headers()["location"]);
  });

  test.afterAll(async ({ request }) => {
    const admin = authHeaders(await tokenFor(request, "admin"));
    // Soft, so a cleanup failure cannot mask a real one — but it must require an
    // actual 2xx/3xx. `catch(() => undefined)` followed by `res === undefined ||
    // ...` scored a request that never completed as a success, which would leak
    // the A2A-enabled fixture agent. That one contaminates specifically: the
    // default Agent Card is whichever A2A agent comes first, so a leftover from an
    // earlier run is exactly what a later run would read.
    const cleanup = async (label: string, call: () => Promise<{ status(): number }>) => {
      const outcome = await call().then(
        (res) => ({ ok: res.status() < 400, detail: `HTTP ${res.status()}` }),
        (err: unknown) => ({ ok: false, detail: `request failed: ${String(err)}` }),
      );
      expect.soft(outcome.ok, `cleanup: ${label} failed — ${outcome.detail}`).toBe(true);
    };
    if (agentId) {
      await cleanup("delete agent", () =>
        request.delete(`${API_BASE}/agentstore/agents/${agentId}?version=1`, { headers: admin }),
      );
    }
    if (workflowId) {
      await cleanup("delete workflow", () =>
        request.delete(`${API_BASE}/workflowstore/workflows/${workflowId}?version=1`, {
          headers: admin,
        }),
      );
    }
  });

  // Local rather than leaning on auth.spec.ts: if this tier were ever pointed at
  // a backend with authorization off, every "anonymous gets 200" assertion below
  // would pass while proving nothing at all.
  test("the backend under test really is enforcing authentication", async ({ request }) => {
    const res = await request.get(`${API_BASE}/agentstore/agents/descriptors`);
    expect(
      res.status(),
      "an anonymous call to a normal API must be 401 here, or every assertion in this file is vacuous",
    ).toBe(401);
  });

  test("an anonymous peer can fetch the default Agent Card", async ({ request }) => {
    const res = await request.get(`${API_BASE}/.well-known/agent.json`);
    expect(
      res.status(),
      "401 means the a2a-agent-card permission entry is gone: @PermitAll alone does not"
        + " exempt a path from quarkus.http.auth.permission.authenticated",
    ).toBe(200);
    expect((await res.json()).name).toBeTruthy();
  });

  test("an anonymous peer can fetch the Agent Card it was pointed at", async ({ request }) => {
    // The exact call A2AToolProviderManager.fetchAgentCard makes against a remote
    // EDDI, where the apiKey is optional — so this is how a peer with no
    // credential for this deployment discovers what the agent can do.
    const res = await request.get(`${API_BASE}/a2a/agents/${agentId}/agent.json`);
    expect(res.status()).toBe(200);

    const card = (await res.json()) as {
      skills?: { id?: string }[];
      authentication?: { credentials?: string };
    };
    expect(card.skills?.map((s) => s.id)).toContain("order-tracking");

    // The token endpoint has to be one THIS caller could reach. The backend's own
    // QUARKUS_OIDC_AUTH_SERVER_URL is http://keycloak:8080/... — resolvable only
    // inside the compose network — so publishing that would strand every external
    // peer. KEYCLOAK_BASE is the published port, i.e. exactly what an outside peer
    // sees, and it is what EDDI_KEYCLOAK_PUBLIC_URL names.
    expect(
      card.authentication?.credentials,
      "the card must advertise a token endpoint reachable from outside the compose network —"
        + " a keycloak:8080 host here means the public issuer derivation regressed",
    ).toBe(`${KEYCLOAK_BASE}/realms/eddi/protocol/openid-connect/token`);
  });

  test("an anonymous caller can reach capability discovery while the flag allows it", async ({
    request,
  }) => {
    // EDDI_A2A_CAPABILITIES_PUBLIC=true in the compose file. That flag is the
    // whole gate — with it off the handler answers 404 to authenticated and
    // anonymous callers alike — so "public" has to mean reachable without a
    // token, or the flag names something it does not do.
    const search = await request.get(`${API_BASE}/.well-known/capabilities?skill=order-tracking`);
    expect(search.status()).toBe(200);
    expect(Array.isArray(await search.json())).toBe(true);

    const skills = await request.get(`${API_BASE}/.well-known/capabilities/skills`);
    expect(skills.status()).toBe(200);
  });

  test("the agent roster is NOT anonymous", async ({ request }) => {
    // Every A2A agent's name, description, skills and URL — strictly more than
    // the skill-name list that sits behind EDDI_A2A_CAPABILITIES_PUBLIC, and no
    // part of the protocol fetches it: a peer is handed a card URL, it does not
    // enumerate. The `@PermitAll` this endpoint used to carry was removed rather
    // than honoured.
    const anonymous = await request.get(`${API_BASE}/a2a/agents`);
    expect(
      anonymous.status(),
      "a permit entry has started matching /a2a/agents — check that a2a-agent-card still uses"
        + " the /a2a/agents/*/agent.json inner wildcard rather than a /a2a/agents/* prefix",
    ).toBe(401);

    // Authenticated, it still works — 401 above is about anonymity, not about
    // the endpoint being broken.
    const authenticated = await request.get(`${API_BASE}/a2a/agents`, {
      headers: authHeaders(await tokenFor(request, "admin")),
    });
    expect(authenticated.status()).toBe(200);
    expect(Array.isArray(await authenticated.json())).toBe(true);
  });

  test("the JSON-RPC surface is NOT anonymous", async ({ request }) => {
    // tasks/send runs a conversation on this deployment's LLM budget. The card
    // endpoints are read-only discovery; this is the door.
    const res = await request.post(`${API_BASE}/a2a/agents/${agentId}`, {
      data: { jsonrpc: "2.0", id: 1, method: "tasks/get", params: { id: "nope" } },
    });
    expect(res.status()).toBe(401);
  });

  test("/.well-known is not wildcarded open", async ({ request }) => {
    // This probed /.well-known/oauth-protected-resource until EDDI began
    // advertising /mcp as an OAuth protected resource. That sibling is now
    // permitted deliberately, by its own narrow entry — so it no longer answers
    // 401, and it is no longer a probe for a wildcard (it 404s: permitted, with
    // the document itself served at the path-inserted form beneath it). The
    // guard is unchanged in substance, on a path nobody has decided on:
    // mcp-oauth.spec.ts and McpOAuthDiscoveryConfigTest cover the entry that was.
    const res = await request.get(`${API_BASE}/.well-known/anything-else`);
    expect(
      res.status(),
      "a sibling of the permitted well-known paths answered without a token — the entry has"
        + " been widened to a wildcard",
    ).toBe(401);
  });
});
