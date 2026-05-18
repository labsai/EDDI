import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Dashboard", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
  });

  test("renders dashboard heading", async ({ page }) => {
    await expectHeading(page, /dashboard/i);
  });

  test("shows stat cards with counts", async ({ page }) => {
    // Stat cards are inside the main content area, not sidebar
    // Each stat card has a font-bold count text
    const counts = page.locator("main .text-2xl.font-bold");
    await expect(counts).toHaveCount(3);
  });

  test("stat card navigates to agents page", async ({ page }) => {
    // Click the agents stat card
    await page.locator('a[href="/manage/agents"]').first().click();
    await expect(page).toHaveURL(/\/manage\/agents/);
  });

  test("quick action buttons are visible", async ({ page }) => {
    // Quick action section has buttons for Agent Wizard, Create Agent, Chat
    await expect(
      page.locator('main a[href="/manage/agents/wizard"]')
    ).toBeVisible();
    await expect(
      page.locator('main a[href="/manage/chat"]')
    ).toBeVisible();
  });

  test("recent agents section shows agent cards", async ({ page }) => {
    // MSW returns 2 agents — they should appear in the recent agents grid
    await expect(page.locator('[data-testid="recent-agents"]').getByText("IT Helpdesk Bot").first()).toBeVisible();
    await expect(page.locator('[data-testid="recent-agents"]').getByText("Support Agent").first()).toBeVisible();
    await expect(page.locator('[data-testid="recent-agents"]').getByText("FAQ Agent").first()).toBeVisible();
    await expect(page.locator('[data-testid="recent-agents"]').getByText("Employee Onboarding Guide").first()).toBeVisible();
  });

  test("clicking recent agent navigates to agent detail", async ({ page }) => {
    const firstAgentCard = page
      .locator('main a[href^="/manage/agentview/"]')
      .first();
    await firstAgentCard.click();
    await expect(page).toHaveURL(/\/manage\/agentview\//);
  });
});
