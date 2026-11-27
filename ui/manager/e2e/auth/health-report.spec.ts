import { expect, test } from "@playwright/test";
import { API_BASE, authHeaders, tokenFor, waitForBackend } from "./auth-helpers";

/**
 * The owner report link's permit rule, on a backend running with OIDC ENFORCED.
 *
 * `quarkus.http.auth.permission.health-report` opens `GET`/`HEAD` of
 * `/health/reports/*` — the public side of an owner report link, whose token is
 * the credential — and nothing else. `RestSharedHealthReportTest` pins the
 * configuration statically; this is what an anonymous browser really gets, in
 * the one tier where the path policies are not switched off.
 */
test.describe("Agent Health owner report — anonymous reachability", () => {
  let agentId: string;
  let workflowId: string;
  let token: string;

  const idOf = (location: string | undefined) => location!.split("/").pop()!.split("?")[0]!;
  const unknownToken = "A".repeat(43);

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
    const admin = authHeaders(await tokenFor(request, "admin"));
    const workflow = await request.post(`${API_BASE}/workflowstore/workflows`, {
      headers: admin,
      data: { workflowSteps: [] },
    });
    expect(workflow.status()).toBe(201);
    workflowId = idOf(workflow.headers()["location"]);
    const agent = await request.post(`${API_BASE}/agentstore/agents`, {
      headers: admin,
      data: { workflows: [workflow.headers()["location"]], description: "Owner report fixture" },
    });
    expect(agent.status()).toBe(201);
    agentId = idOf(agent.headers()["location"]);

    const link = await request.post(`${API_BASE}/agents/${agentId}/health/reports`, {
      headers: admin,
      data: { expiresInDays: 1, sections: ["reliability", "adoption"] },
    });
    expect(link.status()).toBe(201);
    token = ((await link.json()) as { path: string }).path.split("/").pop()!;
    expect(token).toHaveLength(43);
  });

  test.afterAll(async ({ request }) => {
    const admin = authHeaders(await tokenFor(request, "admin"));
    // Deleting the agent revokes its links too; both soft, so cleanup never masks a failure.
    if (agentId) {
      const res = await request.delete(`${API_BASE}/agentstore/agents/${agentId}?version=1&permanent=true`, { headers: admin });
      expect.soft(res.status(), "cleanup: delete agent").toBeLessThan(400);
    }
    if (workflowId) {
      const res = await request.delete(`${API_BASE}/workflowstore/workflows/${workflowId}?version=1`, { headers: admin });
      expect.soft(res.status(), "cleanup: delete workflow").toBeLessThan(400);
    }
  });

  test("the backend under test really is enforcing authentication", async ({ request }) => {
    const res = await request.get(`${API_BASE}/agentstore/agents/descriptors`);
    expect(res.status(), "every assertion below is vacuous unless this is 401").toBe(401);
  });

  test("a live token reads the report anonymously — never cached, one Referrer-Policy, no cookie", async ({ request }) => {
    const res = await request.get(`${API_BASE}/health/reports/${token}`);
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { sections: Record<string, unknown>; status: string | null };
    expect(Object.keys(body.sections)).toEqual(["reliability", "adoption"]);
    const headers = res.headersArray();
    const referrer = headers.filter((h) => h.name.toLowerCase() === "referrer-policy");
    expect(referrer.map((h) => h.value), "exactly one Referrer-Policy, and the strict one").toEqual(["no-referrer"]);
    expect(res.headers()["cache-control"]).toContain("no-store");
    expect(headers.some((h) => h.name.toLowerCase() === "set-cookie"), "no session cookie").toBe(false);
    expect(res.headers()["access-control-allow-origin"]).toBeUndefined();
  });

  test("an unknown or malformed token is a plain 404", async ({ request }) => {
    expect((await request.get(`${API_BASE}/health/reports/${unknownToken}`)).status()).toBe(404);
    expect((await request.get(`${API_BASE}/health/reports/short`)).status()).toBe(404);
  });

  test("only GET and HEAD are permitted on the public path", async ({ request }) => {
    for (const method of ["POST", "PUT", "DELETE", "PATCH"] as const) {
      const res = await request.fetch(`${API_BASE}/health/reports/${token}`, { method, data: {} });
      expect([401, 403, 405], `${method} must be denied`).toContain(res.status());
    }
  });

  test("every other health endpoint stays authenticated", async ({ request }) => {
    for (const path of [
      "/health/agents",
      "/health/presets",
      `/agents/${agentId}/health`,
      `/agents/${agentId}/health/reports`,
      `/agents/${agentId}/health/profile`,
    ]) {
      expect((await request.get(`${API_BASE}${path}`)).status(), `GET ${path}`).toBe(401);
    }
  });

  test("traversal and look-alike paths do not inherit the permit", async ({ request }) => {
    // An encoded slash stays inside the token segment (a malformed token: 404);
    // dot segments are normalised away before the policy is chosen (401).
    for (const path of ["/health/reports/..%2Fagents", "/health/reports/%2e%2e/agents", "/health/reportsX", "/health/reports"]) {
      const res = await request.get(`${API_BASE}${path}`);
      expect([401, 404], `GET ${path}`).toContain(res.status());
      expect(await res.text(), `GET ${path} must not answer with agent data`).not.toContain("agentId");
    }
  });
});
