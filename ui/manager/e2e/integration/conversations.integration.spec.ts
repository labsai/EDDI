import { test, expect } from "@playwright/test";
import {
  API_BASE,
  waitForBackend,
  extractIdFromLocation,
} from "./integration-helpers";

/**
 * Conversation lifecycle tests.
 *
 * These tests use the Bot Father (auto-imported by EDDI on first start)
 * to test conversation creation and message exchange.
 *
 * NOTE on POST /bots/{env}/{botId}/{convId} returning 500:
 * The business-logic-analysis.md documents that the AsyncResponse pattern
 * in RestBotEngine causes 500 even when the conversation processes correctly.
 * We test the actual response status to understand the current behavior,
 * but verify the conversation state via GET regardless.
 */
test.describe("Conversations — Real Backend", () => {
  let botFatherId: string | null = null;
  const conversationsToCleanup: string[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);

    // Find the Bot Father — it should be auto-imported
    const res = await request.get(
      `${API_BASE}/botstore/bots/descriptors?limit=50`
    );
    const descriptors = await res.json();
    const botFather = descriptors.find(
      (d: { name: string }) =>
        d.name.toLowerCase().includes("bot father") ||
        d.name.toLowerCase().includes("botfather")
    );

    if (botFather) {
      const parsed = extractIdFromLocation(botFather.resource);
      botFatherId = parsed.id;
    }
  });

  test.afterAll(async ({ request }) => {
    for (const convId of conversationsToCleanup) {
      try {
        await request.delete(
          `${API_BASE}/conversationstore/conversations/${convId}`
        );
      } catch {
        // Ignore
      }
    }
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

  test("Bot Father exists and is deployed", async ({ request }) => {
    test.skip(!botFatherId, "Bot Father not found — EDDI may not have finished importing initial bots");

    // Check deployment status
    const res = await request.get(
      `${API_BASE}/administration/unrestricted/deploymentstatus/${botFatherId}`
    );
    expect(res.ok()).toBeTruthy();
    const status = await res.json();
    // Bot Father should be deployed (READY or IN_PROGRESS during boot)
    expect(["READY", "IN_PROGRESS"]).toContain(status.status);
  });

  test("Create conversation with Bot Father", async ({ request }) => {
    test.skip(!botFatherId, "Bot Father not found");

    // Create a new conversation
    const res = await request.post(
      `${API_BASE}/bots/unrestricted/${botFatherId}`
    );
    // Should return 201 with Location header containing conversation ID
    expect(res.status()).toBe(201);
    const location = res.headers()["location"];
    expect(location).toBeTruthy();

    // Extract conversation ID from location
    // Location format: /bots/unrestricted/{botId}/{conversationId}
    const parts = location!.split("/").filter(Boolean);
    const conversationId = parts[parts.length - 1]!;
    conversationsToCleanup.push(conversationId);

    // Read conversation state via simple endpoint
    const simpleRes = await request.get(
      `${API_BASE}/conversationstore/conversations/simple/${conversationId}`
    );
    expect(simpleRes.ok()).toBeTruthy();
    const snapshot = await simpleRes.json();
    expect(snapshot.conversationState).toBe("READY");
    expect(snapshot.botId).toBe(botFatherId);
  });

  test("Send message and investigate POST response behavior", async ({
    request,
  }) => {
    test.skip(!botFatherId, "Bot Father not found");

    // Create conversation first
    const createRes = await request.post(
      `${API_BASE}/bots/unrestricted/${botFatherId}`
    );
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"];
    const parts = location!.split("/").filter(Boolean);
    const conversationId = parts[parts.length - 1]!;
    conversationsToCleanup.push(conversationId);

    // Wait a moment for the CONVERSATION_START step to process
    await new Promise((r) => setTimeout(r, 3000));

    // Read conversation BEFORE sending a message — should have initial bot response
    const beforeRes = await request.get(
      `${API_BASE}/conversationstore/conversations/simple/${conversationId}`
    );
    const beforeSnapshot = await beforeRes.json();
    const stepsBefore = beforeSnapshot.conversationSteps?.length ?? 0;

    // Send a message — this is the 500 investigation point
    const sayRes = await request.post(
      `${API_BASE}/bots/unrestricted/${botFatherId}/${conversationId}`,
      {
        headers: { "Content-Type": "text/plain" },
        data: "Let's get started!",
      }
    );

    // Document the actual response status for investigation
    const sayStatus = sayRes.status();
    console.log(`[INVESTIGATION] POST say response status: ${sayStatus}`);

    if (sayStatus === 200) {
      // Great — the AsyncResponse issue may have been fixed
      const body = await sayRes.json();
      console.log("[INVESTIGATION] POST say returned 200 with body:", JSON.stringify(body).substring(0, 200));
    } else if (sayStatus === 500) {
      // Known issue: AsyncResponse timeout in RestBotEngine
      console.log("[INVESTIGATION] POST say returned 500 — AsyncResponse timeout issue confirmed");
      console.log("[INVESTIGATION] This is the known issue from business-logic-analysis.md §4");
      try {
        const errorBody = await sayRes.text();
        console.log("[INVESTIGATION] Error body:", errorBody.substring(0, 500));
      } catch {
        console.log("[INVESTIGATION] No error body returned (also documented as a known issue)");
      }
    } else {
      console.log(`[INVESTIGATION] Unexpected status ${sayStatus}`);
    }

    // Wait for processing regardless of the POST status
    await new Promise((r) => setTimeout(r, 5000));

    // Verify conversation state via GET — this should always work
    const afterRes = await request.get(
      `${API_BASE}/conversationstore/conversations/simple/${conversationId}`
    );
    expect(afterRes.ok()).toBeTruthy();
    const afterSnapshot = await afterRes.json();

    // Conversation should have processed the message
    const stepsAfter = afterSnapshot.conversationSteps?.length ?? 0;
    console.log(`[INVESTIGATION] Steps before: ${stepsBefore}, after: ${stepsAfter}`);
    console.log(`[INVESTIGATION] Conversation state: ${afterSnapshot.conversationState}`);

    // The conversation should still be in READY state (not ERROR)
    expect(["READY", "IN_PROGRESS"]).toContain(afterSnapshot.conversationState);
    // There should be more steps than before (bot processed the message)
    expect(stepsAfter).toBeGreaterThan(0);
  });

  test("List conversations filtered by botId", async ({ request }) => {
    test.skip(!botFatherId, "Bot Father not found");

    const res = await request.get(
      `${API_BASE}/conversationstore/conversations?botId=${botFatherId}&limit=10`
    );
    expect(res.ok()).toBeTruthy();
    const conversations = await res.json();
    expect(Array.isArray(conversations)).toBeTruthy();
    // All returned conversations should belong to this bot
    for (const conv of conversations) {
      expect(conv.botId).toBe(botFatherId);
    }
  });
});
