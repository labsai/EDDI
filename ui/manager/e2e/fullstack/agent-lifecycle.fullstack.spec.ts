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
  let workflowVersion: number;
  const conversationsToCleanup: string[] = [];

  test.beforeAll(async ({ request }) => {
    // Create and deploy a test agent via API (faster than wizard for setup)
    // createAndDeployAgent already calls waitForBackend internally
    const deployed = await createAndDeployAgent(request);
    agentId = deployed.agentId;
    agentVersion = deployed.agentVersion;
    workflowId = deployed.workflowId;
    workflowVersion = deployed.workflowVersion;
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
      "workflowstore/workflows",
      workflowId,
      workflowVersion
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
      `${API_BASE}/agents/${agentId}/start`
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
      `${API_BASE}/agents/${agentId}/start`
    );
    expect(createRes.status()).toBe(201);
    const location = createRes.headers()["location"]!;
    const convId = location.split("/").filter(Boolean).pop()!;
    conversationsToCleanup.push(convId);

    await navigateTo(page, "/manage/conversations");

    // The conversations list defaults to CARDS, not a table (getStoredViewMode
    // falls back to "card" and a fresh browser profile has no localStorage).
    const grid = page.getByTestId("conversation-grid");
    await expect(grid).toBeVisible({ timeout: 15_000 });
    await expect(grid.getByTestId(/^conversation-card-/).first()).toBeVisible({
      timeout: 15_000,
    });
  });

  test("conversation detail renders steps", async ({ page, request }) => {
    // This used to read back whichever conversation the previous test happened
    // to leave behind and `test.skip()` when it found none — so a broken create
    // path, or a reordering, turned into a green skip. It now creates the
    // conversation it needs, which is both deterministic and order-independent.
    const createRes = await request.post(
      `${API_BASE}/agents/${agentId}/start`
    );
    expect(
      createRes.status(),
      "could not create the conversation this test renders",
    ).toBe(201);
    const convId = createRes.headers()["location"]!.split("/").filter(Boolean).pop()!;
    conversationsToCleanup.push(convId);

    // A conversation with no steps renders an empty transcript, so creating one
    // and asserting only the page shell would leave this test unable to fail on
    // its own name. Drive one real turn through the deployed agent first —
    // plain text, which is what `sendMessage` posts.
    const turn = `detail-view probe ${convId}`;
    const sendRes = await request.post(
      `${API_BASE}/agents/${convId}?returnDetailed=false&returnCurrentStepOnly=true`,
      { headers: { "Content-Type": "text/plain" }, data: turn }
    );
    expect(
      sendRes.ok(),
      `could not send a turn to ${convId} (HTTP ${sendRes.status()})`,
    ).toBe(true);

    await navigateTo(page, `/manage/conversationview/${convId}`);

    // The step this test is named for: the turn we just sent has to be on screen.
    await expect(page.getByTestId("conversation-chat")).toContainText(turn, {
      timeout: 15_000,
    });

    // Back link should be present
    await expect(
      page.getByText(/back to conversations/i)
    ).toBeVisible();
  });
});
