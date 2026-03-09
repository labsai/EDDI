import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Bots Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/bots");
    await waitForApp(page);
  });

  test("renders bots heading", async ({ page }) => {
    await expectHeading(page, /bots/i);
  });

  test("shows bot cards with mock data", async ({ page }) => {
    // MSW returns 2 bots: "Support Bot" and "FAQ Bot"
    await expect(page.getByText("Support Bot")).toBeVisible();
    await expect(page.getByText("FAQ Bot")).toBeVisible();
  });

  test("search input is functional", async ({ page }) => {
    // Find the search input and verify it works
    const searchInput = page.locator('main input[type="text"]').first();
    await searchInput.fill("Support");
    await expect(searchInput).toHaveValue("Support");
  });

  test("create bot button is visible", async ({ page }) => {
    await expect(
      page.getByText(/create bot/i).first()
    ).toBeVisible();
  });

  test("bot card click navigates to bot detail", async ({ page }) => {
    await page.getByText("Support Bot").click();
    await expect(page).toHaveURL(/\/manage\/botview\//);
  });

  test("bot wizard button is visible", async ({ page }) => {
    await expect(
      page.getByText(/bot wizard/i).first()
    ).toBeVisible();
  });

  test("import button is visible", async ({ page }) => {
    await expect(
      page.getByText(/import/i).first()
    ).toBeVisible();
  });
});

test.describe("Bot Wizard", () => {
  test("wizard page loads", async ({ page }) => {
    await page.goto("/manage/bots/wizard");
    await waitForApp(page);

    await expect(page.locator("h1")).toContainText(/wizard|create/i);
  });
});
