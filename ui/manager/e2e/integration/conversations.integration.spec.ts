import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  createAndDeployAgent,
  cleanupResource,
} from "./integration-helpers";

/**
 * Conversation lifecycle tests.
 * Fully self-contained — creates and deploys its own agent,
 * no dependency on Agent Father.
 *
 * POST /agents/{env}/{agentId}/{convId} returns 200 with full conversation JSON
 * snapshot in v6. (The pre-v6 AsyncResponse 500 timeout issue is resolved.)
 */
test.describe("Conversations — Real Backend", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });

  let agentId: string;
  let agentVersion: number;
  let workflowId: string;
  let packageVersion: number;
  const conversationsToCleanup: string[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);

    // Create and deploy our own agent — no Agent Father dependency
    const deployed = await createAndDeployAgent(request);
    agentId = deployed.agentId;
    agentVersion = deployed.agentVersion;
    workflowId = deployed.workflowId;
    packageVersion = deployed.packageVersion;
  });

  test.afterAll(async ({ request }) => {
    // Undeploy
    try {
      await request.post(
        `${API_BASE}/administration/unrestricted/undeploy/${agentId}?version=${agentVersion}`
      );
    } catch {
      /* ignore */
    }
    // Delete conversations
    for (const convId of conversationsToCleanup) {
      try {
        await request.delete(
          `${API_BASE}/conversationstore/conversations/${convId}`
        );
      } catch {
        /* ignore */
      }
    }
    // Delete agent and package
    await cleanupResource(request, "agentstore/agents", agentId, agentVersion);
    await cleanupResource(
      request,
      "packagestore/packages",
      workflowId,
      packageVersion
    );
  });

  test("GET /conversationstore/conversations returns descriptors", async ({
    request,
  }) => {
    const res = await request.get(
      `${API_BASE}/conversationstore/conversations?limit=10`
    );
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(Array.isArray(body)).toBeTruthy();
  });

  test("Create conversation returns 201 with Location", async ({
    request,
  }) => {
    const res = await request.post(
      `${API_BASE}/agents/unrestricted/${agentId}`
    );
    expect(res.status()).toBe(201);
    const location = res.headers()["location"];
    expect(location).toBeTruthy();

    // Extract conversation ID
    const parts = location!.split("/").filter(Boolean);
    const conversationId = parts[parts.length - 1]!;
    conversationsToCleanup.push(conversationId);
  });

  test("Send message returns 200 with conversation snapshot (v6 fix confirmed)", async ({
    request,
  }) => {
    // Create conversation
    const createRes = await request.post(
      `${API_BASE}/agents/unrestricted/${agentId}`
    );
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"]!;
    const parts = location.split("/").filter(Boolean);
    const conversationId = parts[parts.length - 1]!;
    conversationsToCleanup.push(conversationId);

    // Wait for initial processing
    await new Promise((r) => setTimeout(r, 2000));

    // Send message — returns 200 with full conversation snapshot
    const sayRes = await request.post(
      `${API_BASE}/agents/unrestricted/${agentId}/${conversationId}`,
      {
        headers: { "Content-Type": "text/plain" },
        data: "Hello from integration test!",
      }
    );

    expect(sayRes.status()).toBe(200);
    const snapshot = await sayRes.json();
    expect(snapshot.conversationId).toBe(conversationId);
    expect(snapshot.agentId).toBe(agentId);
    expect(snapshot.conversationState).toBe("READY");
    expect(snapshot.conversationSteps.length).toBeGreaterThan(0);
  });

  test("Read conversation state via simple endpoint", async ({ request }) => {
    // Create conversation
    const createRes = await request.post(
      `${API_BASE}/agents/unrestricted/${agentId}`
    );
    const location = createRes.headers()["location"]!;
    const convId = location.split("/").filter(Boolean).pop()!;
    conversationsToCleanup.push(convId);

    // Wait for initial step
    await new Promise((r) => setTimeout(r, 2000));

    const res = await request.get(
      `${API_BASE}/conversationstore/conversations/simple/${convId}`
    );
    expect(res.ok()).toBeTruthy();
    const snapshot = await res.json();
    expect(snapshot.conversationState).toBe("READY");
    expect(snapshot.agentId).toBe(agentId);
  });

  test("List conversations filtered by agentId", async ({ request }) => {
    const res = await request.get(
      `${API_BASE}/conversationstore/conversations?agentId=${agentId}&limit=10`
    );
    expect(res.ok()).toBeTruthy();
    const conversations = await res.json();
    expect(Array.isArray(conversations)).toBeTruthy();
    // All returned conversations should belong to this agent
    for (const conv of conversations) {
      expect(conv.agentId).toBe(agentId);
    }
  });
});
