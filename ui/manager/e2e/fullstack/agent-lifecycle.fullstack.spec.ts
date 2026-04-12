import { test, expect } from "@playwright/test";
import {
  waitForFullStack,
  navigateTo,
  API_BASE,
} from "./fullstack-helpers";
import {
  cleanupResource,
  createAndDeployAgent,
} from "../integration/integration-helpers";

/**
 * Agent lifecycle — the critical happy path tested end-to-end
 * through the browser against a real EDDI backend.
 *
 * Flow: Create agent → deploy → chat → verify conversation → cleanup
 */
test.describe("Agent Lifecycle — Full Stack", () => {
  test.describe.configure({ timeout: 180_000, mode: "serial" });

  let agentId: string;
  let agentVersion: number;
  let workflowId: string;
  let packageVersion: number;
  const conversationsToCleanup: string[] = [];

  test.beforeAll(async ({ request }) => {
    // Create and deploy a test agent via API (faster than wizard for setup)
    // createAndDeployAgent already calls waitForBackend internally
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
        `${API_BASE}/administration/production/undeploy/${agentId}?version=${agentVersion}`
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
    await cleanupResource(
      request,
      "agentstore/agents",
      agentId,
      agentVersion
    );
    await cleanupResource(
      request,
      "packagestore/packages",
      workflowId,
      packageVersion
    );
  });

  test("agent appears in Agents list page", async ({ page, request }) => {
    // Backend already confirmed in beforeAll — skip redundant health check
    await waitForFullStack(page, request, "/manage/agents", {
      skipHealthCheck: true,
    });

    // The descriptors endpoint returns the agent — it should render in the UI
    const agentLinks = page.locator('main a[href*="/manage/agentview/"]');
    await expect(agentLinks.first()).toBeVisible({ timeout: 10_000 });
  });

  test("agent detail page shows correct data", async ({ page }) => {
    await navigateTo(page, `/manage/agentview/${agentId}`);

    // Should show agent detail heading
    await expect(page.locator("h1")).toBeVisible();

    // Should show workflows/packages section
    await expect(
      page.locator("main").getByText(/workflow/i).first()
    ).toBeVisible();
  });

  test("agent detail shows deployment status", async ({ page }) => {
    await navigateTo(page, `/manage/agentview/${agentId}`);

    // The agent was deployed in beforeAll — status badge should reflect it
    await expect(
      page.getByText(/deployed|ready/i).first()
    ).toBeVisible({ timeout: 10_000 });
  });

  test("chat page loads and allows agent selection", async ({ page }) => {
    await navigateTo(page, "/manage/chat");

    // Agent selector should be visible
    const agentSelector = page.getByTestId("agent-selector");
    await expect(agentSelector).toBeVisible({ timeout: 10_000 });

    // Open the selector — our deployed agent should appear
    await agentSelector.click();

    // Wait for dropdown options to render (real API call)
    const firstOption = page.locator('[role="option"]').first();
    await expect(firstOption).toBeVisible({ timeout: 10_000 });
  });

  test("can start conversation and send message via chat UI", async ({
    page,
    request,
  }) => {
    // Create conversation via API first so we can track the ID for cleanup
    const createRes = await request.post(
      `${API_BASE}/agents/production/${agentId}`
    );
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"]!;
    const convId = location.split("/").filter(Boolean).pop()!;
    conversationsToCleanup.push(convId);

    await navigateTo(page, "/manage/chat");

    const agentSelector = page.getByTestId("agent-selector");
    await agentSelector.click();

    // Select the first available agent from the dropdown
    const firstOption = page.locator('[role="option"]').first();
    await expect(firstOption).toBeVisible({ timeout: 10_000 });
    await firstOption.click();

    // Wait for chat input to appear (conversation ready)
    const chatInput = page.getByTestId("chat-input");
    await expect(chatInput).toBeVisible({ timeout: 30_000 });

    // Type and send a message
    await chatInput.fill("Hello from full-stack E2E test!");
    await chatInput.press("Enter");

    // Wait for the message to appear in the chat (not a hard timeout)
    await expect(
      page.getByText("Hello from full-stack E2E test!").first()
    ).toBeVisible({ timeout: 15_000 });
  });

  test("conversation appears in Conversations list", async ({
    page,
    request,
  }) => {
    // Create a conversation via API so we have a known one to find
    const createRes = await request.post(
      `${API_BASE}/agents/production/${agentId}`
    );
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"]!;
    const convId = location.split("/").filter(Boolean).pop()!;
    conversationsToCleanup.push(convId);

    await navigateTo(page, "/manage/conversations");

    // The conversations table should show at least one row (auto-retries)
    const table = page.locator("table");
    await expect(table).toBeVisible({ timeout: 15_000 });
    await expect(table.locator("tbody tr")).not.toHaveCount(0);
  });

  test("conversation detail renders steps", async ({ page, request }) => {
    // Get conversations for our agent
    const listRes = await request.get(
      `${API_BASE}/conversationstore/conversations?agentId=${agentId}&limit=1`
    );
    const conversations = await listRes.json();
    if (!conversations.length) {
      test.skip();
      return;
    }

    const convId = conversations[0].conversationId || conversations[0].id;
    await navigateTo(page, `/manage/conversationview/${convId}`);

    // Should show conversation detail content
    await expect(page.locator("main")).toBeVisible();
    // Back link should be present
    await expect(
      page.getByText(/back to conversations/i)
    ).toBeVisible();
  });
});
