import { test, expect } from "@playwright/test";
import { waitForApp } from "./e2e-helpers";

test.describe("Bot Detail", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/botview/bot1");
    await waitForApp(page);
  });

  test("renders bot detail heading", async ({ page }) => {
    await expect(page.locator("h1")).toContainText(/bot detail/i);
  });

  test("shows back link to bots list", async ({ page }) => {
    await expect(page.getByText(/back to bots/i)).toBeVisible();
  });

  test("shows version selector", async ({ page }) => {
    // VersionSelect renders as version-badge (single ver) or version-picker (multi ver)
    const badge = page.getByTestId("version-badge");
    const picker = page.getByTestId("version-picker");
    const either = badge.or(picker);
    await expect(either.first()).toBeVisible();
  });

  test("shows deployment status", async ({ page }) => {
    // The status badge says "Deployed"
    await expect(page.getByText("Deployed").first()).toBeVisible();
  });

  test("shows environments section with environment names", async ({ page }) => {
    await expect(page.getByText("Environments")).toBeVisible();
    await expect(page.getByText("Unrestricted")).toBeVisible();
  });

  test("shows packages section", async ({ page }) => {
    // Use heading text to avoid matching sidebar "Packages" link
    await expect(page.locator("main").getByText("Packages").first()).toBeVisible();
    await expect(page.getByText(/add package/i)).toBeVisible();
  });

  test("shows action buttons", async ({ page }) => {
    await expect(page.getByText("Duplicate")).toBeVisible();
    await expect(page.getByText("Export")).toBeVisible();
  });

  test("shows raw configuration accordion", async ({ page }) => {
    await expect(page.getByText(/raw configuration/i)).toBeVisible();
  });

  test("back link navigates to bots list", async ({ page }) => {
    await page.getByText(/back to bots/i).click();
    await expect(page).toHaveURL(/\/manage\/bots/);
  });
});
