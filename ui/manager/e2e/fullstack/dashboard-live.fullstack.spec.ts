import { test, expect } from "@playwright/test";
import {
  waitForFullStack,
  navigateTo,
  API_BASE,
  waitForBackend,
} from "./fullstack-helpers";
import {
  extractIdFromLocation,
  cleanupResource,
} from "../integration/integration-helpers";

/**
 * Dashboard page with real backend data.
 * Verifies stat cards, recent agents, and navigation work with live data.
 */
test.describe("Dashboard — Full Stack", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });

  const createdAgents: { id: string; version: number }[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test.afterAll(async ({ request }) => {
    for (const agent of createdAgents) {
      await cleanupResource(
        request,
        "agentstore/agents",
        agent.id,
        agent.version
      );
    }
  });

  test("dashboard renders with real statistics", async ({ page, request }) => {
    await waitForFullStack(page, request, "/manage", {
      skipHealthCheck: true,
    });

    // Dashboard heading
    await expect(page.locator("h1")).toContainText(/dashboard/i);

    // Stat cards should be present with numeric counts from real data
    const statCards = page.locator("main .text-2xl.font-bold");
    await expect(statCards.first()).toBeVisible({ timeout: 10_000 });
  });

  test("stat card counts reflect real data", async ({ page, request }) => {
    // Get the real agent count from the API
    const agentsRes = await request.get(
      `${API_BASE}/agentstore/agents/descriptors?limit=1000`
    );
    const agents = await agentsRes.json();
    const realAgentCount = agents.length;

    await navigateTo(page, "/manage");

    // Scope assertion to the agents stat card link specifically,
    // not the entire main content (avoids false positives from timestamps, IDs, etc.)
    const agentStatCard = page.locator('main a[href="/manage/agents"]').first();
    await expect(agentStatCard).toContainText(String(realAgentCount));
  });

  test("creating an agent updates dashboard count", async ({
    page,
    request,
  }) => {
    // Get current count
    const beforeRes = await request.get(
      `${API_BASE}/agentstore/agents/descriptors?limit=1000`
    );
    const beforeCount = (await beforeRes.json()).length;

    // Create a new agent
    const createRes = await request.post(`${API_BASE}/agentstore/agents`, {
      data: { packages: [], channels: [] },
    });
    expect(createRes.status()).toBe(201);
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    createdAgents.push({ id, version });

    // Refresh dashboard and verify the count in the agents stat card increased
    await navigateTo(page, "/manage");
    const agentStatCard = page.locator('main a[href="/manage/agents"]').first();
    await expect(agentStatCard).toContainText(String(beforeCount + 1));
  });

  test("quick action buttons navigate correctly", async ({ page }) => {
    await navigateTo(page, "/manage");

    // Agent wizard link should work
    const wizardLink = page.locator('main a[href="/manage/agents/wizard"]');
    if ((await wizardLink.count()) > 0) {
      await wizardLink.click();
      await expect(page).toHaveURL(/\/manage\/agents\/wizard/);
    }
  });

  test("stat card navigates to agents page", async ({ page }) => {
    await navigateTo(page, "/manage");

    const agentLink = page.locator('main a[href="/manage/agents"]').first();
    if ((await agentLink.count()) > 0) {
      await agentLink.click();
      await expect(page).toHaveURL(/\/manage\/agents/);
    }
  });
});
