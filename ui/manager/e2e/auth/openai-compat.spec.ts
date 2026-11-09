import { expect, test } from "@playwright/test";
import { API_BASE, authHeaders, tokenFor, waitForBackend } from "./auth-helpers";

/**
 * The OpenAI-compatible adapter (`/v1`) with its shared key, on a backend running
 * with OIDC ENFORCED — the Keycloak + Open WebUI setup.
 *
 * In the default `eddi.openai-compat.http-policy=permit`, the adapter checks the
 * shared key itself, which is why `/v1/*` has its own `permit` permission. That
 * was not enough: Quarkus authenticates proactively, so the OIDC bearer
 * mechanism still received `Authorization: Bearer <key>`, failed to verify it as
 * a token and answered 401 with a `WWW-Authenticate` challenge before the
 * permission was consulted or the adapter ran. Requests WITHOUT the header did
 * reach the adapter, so a check that only probed the unauthenticated path saw
 * nothing wrong. Open WebUI cannot mint per-user OIDC tokens, so its connection
 * — the main production setup — could not work at all (security review B7).
 * `OpenAiPermitModeAuthMechanism` now keeps OIDC off `/v1` in permit mode.
 *
 * The compose file enables the adapter with this key; the value is a fixture.
 */
const SHARED_KEY = "e2e-shared-key";
const OPEN_WEBUI_USER = "e2e-openwebui-user";

const sharedKeyHeaders = (key = SHARED_KEY) => ({
  ...authHeaders(key),
  "X-OpenWebUI-User-Id": OPEN_WEBUI_USER,
});

type OpenAiError = { error?: { code?: string; message?: string } };

test.describe("OpenAI-compatible API — shared key under OIDC", () => {
  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test("the shared key reaches the adapter and is served", async ({ request }) => {
    const res = await request.get(`${API_BASE}/v1/models`, { headers: sharedKeyHeaders() });
    expect(
      res.status(),
      "a 401 here, with a WWW-Authenticate header, is the OIDC layer refusing the key as a"
        + ` malformed token — the B7 regression. Body: ${await res.text()}`,
    ).toBe(200);
    expect(((await res.json()) as { object?: string }).object).toBe("list");
  });

  test("a wrong key gets the adapter's 401, not an OIDC challenge", async ({ request }) => {
    const res = await request.get(`${API_BASE}/v1/models`, {
      headers: sharedKeyHeaders("not-the-shared-key"),
    });
    expect(res.status()).toBe(401);
    // The adapter answers in the OpenAI envelope and sends no challenge; the OIDC
    // mechanism's 401 has an empty body and `WWW-Authenticate: Bearer`.
    expect(res.headers()["www-authenticate"]).toBeUndefined();
    expect(((await res.json()) as OpenAiError).error?.code).toBe("invalid_api_key");
  });

  test("no key gets the adapter's 401", async ({ request }) => {
    const res = await request.get(`${API_BASE}/v1/models`, {
      headers: { "X-OpenWebUI-User-Id": OPEN_WEBUI_USER },
    });
    expect(res.status()).toBe(401);
    expect(((await res.json()) as OpenAiError).error?.code).toBe("invalid_api_key");
  });

  test("a Keycloak token is not the shared key in permit mode", async ({ request }) => {
    // Intended: in permit mode the adapter authenticates, and a JWT is simply a
    // wrong key. Per-user tokens are what http-policy=authenticated is for.
    const res = await request.get(`${API_BASE}/v1/models`, {
      headers: {
        ...authHeaders(await tokenFor(request, "user")),
        "X-OpenWebUI-User-Id": OPEN_WEBUI_USER,
      },
    });
    expect(res.status()).toBe(401);
    expect(((await res.json()) as OpenAiError).error?.code).toBe("invalid_api_key");
  });

  test("the shared key opens nothing outside /v1", async ({ request }) => {
    // The mechanism only acts on /v1. Everywhere else OIDC still verifies the
    // bearer token, so the key is the garbage token it always was there.
    const res = await request.get(`${API_BASE}/agentstore/agents/descriptors`, {
      headers: sharedKeyHeaders(),
    });
    expect(res.status()).toBe(401);
    expect(res.headers()["www-authenticate"]).toBeDefined();
  });

  test("a chat completion runs end to end with the shared key", async ({ request }) => {
    // A full turn, not just the model list: the conversation runs while the
    // request carries the anonymous identity, on a backend where authorization
    // is enabled — the combination no other tier exercises.
    const admin = authHeaders(await tokenFor(request, "admin"));
    const idOf = (location: string | undefined) => location!.split("/").pop()!.split("?")[0]!;

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

      const res = await request.post(`${API_BASE}/v1/chat/completions`, {
        headers: {
          ...sharedKeyHeaders(),
          "X-OpenWebUI-Chat-Id": `e2e-openai-compat-${agentId}`,
        },
        // The bare agentId is an accepted model value (AgentModelResolver step 2).
        data: { model: agentId, messages: [{ role: "user", content: "hello" }] },
      });
      expect(res.status(), await res.text()).toBe(200);
      expect(((await res.json()) as { object?: string }).object).toBe("chat.completion");
      conversationId = res.headers()["x-eddi-conversation-id"];
    } finally {
      // Soft, so a cleanup failure cannot replace the error that sent us here.
      const cleanup = async (label: string, call: () => Promise<{ status(): number }>) => {
        const outcome = await call().then(
          (r) => ({ ok: r.status() < 400, detail: `HTTP ${r.status()}` }),
          (err: unknown) => ({ ok: false, detail: `request failed: ${String(err)}` }),
        );
        expect.soft(outcome.ok, `cleanup: ${label} failed — ${outcome.detail}`).toBe(true);
      };
      await cleanup("undeploy", () =>
        request.post(
          `${API_BASE}/administration/production/undeploy/${agentId}?version=1&endAllActiveConversations=true`,
          { headers: admin },
        ),
      );
      if (conversationId) {
        const id = conversationId;
        await cleanup("delete conversation", () =>
          request.delete(`${API_BASE}/conversationstore/conversations/${id}`, { headers: admin }),
        );
      }
      await cleanup("delete agent", () =>
        request.delete(`${API_BASE}/agentstore/agents/${agentId}?version=1`, { headers: admin }),
      );
      await cleanup("delete workflow", () =>
        request.delete(`${API_BASE}/workflowstore/workflows/${workflowId}?version=1`, { headers: admin }),
      );
    }
  });
});
