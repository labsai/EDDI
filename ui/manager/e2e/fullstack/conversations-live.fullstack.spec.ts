import { test, expect } from "@playwright/test";
import {
  waitForFullStack,
  navigateTo,
  API_BASE,
} from "./fullstack-helpers";
import {
  createAndDeployAgent,
  cleanupResource,
} from "../integration/integration-helpers";

/**
 * Conversations page tested with real backend data.
 * Creates a deployed agent, starts conversations, and verifies
 * the UI renders, filters, and navigates correctly.
 */
test.describe("Conversations — Full Stack", () => {
  test.describe.configure({ timeout: 180_000, mode: "serial" });

  let agentId: string;
  let agentVersion: number;
  let workflowId: string;
  let workflowVersion: number;
  const conversationsToCleanup: string[] = [];

  test.beforeAll(async ({ request }) => {
    const deployed = await createAndDeployAgent(request);
    agentId = deployed.agentId;
    agentVersion = deployed.agentVersion;
    workflowId = deployed.workflowId;
    workflowVersion = deployed.workflowVersion;

    // Create 2 conversations for testing
    for (let i = 0; i < 2; i++) {
      const res = await request.post(
        `${API_BASE}/agents/${agentId}/start`
      );
      expect(res.status()).toBe(201);
      const location = res.headers()["location"]!;
      const convId = location.split("/").filter(Boolean).pop()!;
      conversationsToCleanup.push(convId);
    }

    // Wait for initial processing
    await new Promise((r) => setTimeout(r, 3000));
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

  test("conversations page shows table with real data", async ({
    page,
    request,
  }) => {
    // Backend already confirmed in beforeAll
    await waitForFullStack(page, request, "/manage/conversations", {
      skipHealthCheck: true,
    });

    // The default view is CARDS, not a table: getStoredViewMode falls back to
    // "card" and this browser profile has no localStorage, so `page.locator("table")`
    // matched nothing and this test has been red on every push to main. Assert
    // what the page actually renders by default.
    const grid = page.getByTestId("conversation-grid");
    await expect(grid).toBeVisible({ timeout: 15_000 });

    // The conversations THIS test created, by id — not merely "a card exists",
    // which any leftover from any previous run against this backend satisfies.
    // The card carries the id in its testid (src/pages/conversations.tsx).
    for (const convId of conversationsToCleanup) {
      await expect(grid.getByTestId(`conversation-card-${convId}`)).toBeVisible({
        timeout: 15_000,
      });
    }
  });

  test("conversations table shows correct state badges", async ({ page }) => {
    await navigateTo(page, "/manage/conversations");

    // Newly created conversations should be in READY state (shown as "Active")
    await expect(page.getByText("Active").first()).toBeVisible({
      timeout: 10_000,
    });
  });

  test("search input is present and functional", async ({ page }) => {
    await navigateTo(page, "/manage/conversations");

    const search = page.getByTestId("conversation-search");
    await expect(search).toBeVisible();
    await search.fill("test");
    await expect(search).toHaveValue("test");
  });

  test("state filter buttons are visible", async ({ page }) => {
    await navigateTo(page, "/manage/conversations");

    await expect(
      page.getByRole("button", { name: /^all$/i })
    ).toBeVisible();
  });

  test("clicking conversation navigates to detail", async ({ page }) => {
    await navigateTo(page, "/manage/conversations");

    // Cards, not rows — the default view has no table. Each card IS the link.
    const firstConvLink = page.getByTestId(/^conversation-card-/).first();
    await expect(firstConvLink).toBeVisible({ timeout: 15_000 });
    await firstConvLink.click();
    await expect(page).toHaveURL(/\/manage\/conversationview\//);
  });

  test("conversation detail shows back link", async ({ page }) => {
    const convId = conversationsToCleanup[0];
    await navigateTo(page, `/manage/conversationview/${convId}`);

    await expect(
      page.getByText(/back to conversations/i)
    ).toBeVisible();
  });

  test("back link returns to conversations list", async ({ page }) => {
    const convId = conversationsToCleanup[0];
    await navigateTo(page, `/manage/conversationview/${convId}`);

    await page.getByText(/back to conversations/i).click();
    await expect(page).toHaveURL(/\/manage\/conversations/);
  });
});
