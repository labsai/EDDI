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
    await expect(counts).toHaveCount(4);
  });

  test("stat card navigates to bots page", async ({ page }) => {
    // Click the bots stat card
    await page.locator('main a[href="/manage/bots"]').first().click();
    await expect(page).toHaveURL(/\/manage\/bots/);
  });

  test("quick action buttons are visible", async ({ page }) => {
    // Quick action section has buttons for Bot Wizard, Create Bot, Chat
    await expect(
      page.locator('main a[href="/manage/bots/wizard"]')
    ).toBeVisible();
    await expect(
      page.locator('main a[href="/manage/chat"]')
    ).toBeVisible();
  });

  test("recent bots section shows bot cards", async ({ page }) => {
    // MSW returns 2 bots — they should appear in the recent bots grid
    await expect(page.getByText("Support Bot")).toBeVisible();
    await expect(page.getByText("FAQ Bot")).toBeVisible();
  });

  test("clicking recent bot navigates to bot detail", async ({ page }) => {
    const firstBotCard = page
      .locator('main a[href^="/manage/botview/"]')
      .first();
    await firstBotCard.click();
    await expect(page).toHaveURL(/\/manage\/botview\//);
  });
});
