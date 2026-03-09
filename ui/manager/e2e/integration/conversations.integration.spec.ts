import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  createAndDeployBot,
  cleanupResource,
} from "./integration-helpers";

/**
 * Conversation lifecycle tests.
 * Fully self-contained — creates and deploys its own bot,
 * no dependency on Bot Father.
 *
 * KEY FINDING: POST /bots/{env}/{botId}/{convId} returns 200 in v6 —
 * the AsyncResponse 500 issue documented in business-logic-analysis.md is FIXED.
 */
test.describe("Conversations — Real Backend", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });

  let botId: string;
  let botVersion: number;
  let packageId: string;
  let packageVersion: number;
  const conversationsToCleanup: string[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);

    // Create and deploy our own bot — no Bot Father dependency
    const deployed = await createAndDeployBot(request);
    botId = deployed.botId;
    botVersion = deployed.botVersion;
    packageId = deployed.packageId;
    packageVersion = deployed.packageVersion;
  });

  test.afterAll(async ({ request }) => {
    // Undeploy
    try {
      await request.post(
        `${API_BASE}/administration/unrestricted/undeploy/${botId}?version=${botVersion}`
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
    // Delete bot and package
    await cleanupResource(request, "botstore/bots", botId, botVersion);
    await cleanupResource(
      request,
      "packagestore/packages",
      packageId,
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
      `${API_BASE}/bots/unrestricted/${botId}`
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
      `${API_BASE}/bots/unrestricted/${botId}`
    );
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"]!;
    const parts = location.split("/").filter(Boolean);
    const conversationId = parts[parts.length - 1]!;
    conversationsToCleanup.push(conversationId);

    // Wait for initial processing
    await new Promise((r) => setTimeout(r, 2000));

    // Send message — this used to return 500 (AsyncResponse timeout),
    // now returns 200 with full conversation snapshot in v6!
    const sayRes = await request.post(
      `${API_BASE}/bots/unrestricted/${botId}/${conversationId}`,
      {
        headers: { "Content-Type": "text/plain" },
        data: "Hello from integration test!",
      }
    );

    expect(sayRes.status()).toBe(200);
    const snapshot = await sayRes.json();
    expect(snapshot.conversationId).toBe(conversationId);
    expect(snapshot.botId).toBe(botId);
    expect(snapshot.conversationState).toBe("READY");
    expect(snapshot.conversationSteps.length).toBeGreaterThan(0);
  });

  test("Read conversation state via simple endpoint", async ({ request }) => {
    // Create conversation
    const createRes = await request.post(
      `${API_BASE}/bots/unrestricted/${botId}`
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
    expect(snapshot.botId).toBe(botId);
  });

  test("List conversations filtered by botId", async ({ request }) => {
    const res = await request.get(
      `${API_BASE}/conversationstore/conversations?botId=${botId}&limit=10`
    );
    expect(res.ok()).toBeTruthy();
    const conversations = await res.json();
    expect(Array.isArray(conversations)).toBeTruthy();
    // All returned conversations should belong to this bot
    for (const conv of conversations) {
      expect(conv.botId).toBe(botId);
    }
  });
});
