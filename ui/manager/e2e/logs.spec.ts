import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Logs Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/logs");
    await waitForApp(page);
  });

  test("renders logs heading", async ({ page }) => {
    await expectHeading(page, /logs/i);
  });

  test("shows live and history tabs", async ({ page }) => {
    // Use the actual data-testids from the logs page
    await expect(page.getByTestId("tab-live")).toBeVisible();
    await expect(page.getByTestId("tab-history")).toBeVisible();
  });

  test("shows logs page container", async ({ page }) => {
    await expect(page.getByTestId("logs-page")).toBeVisible();
  });

  test("shows level filter dropdown", async ({ page }) => {
    // The live tab shows a level filter select
    await expect(page.getByTestId("filter-level")).toBeVisible();
  });

  test("has pause/resume streaming control", async ({ page }) => {
    await expect(page.getByTestId("pause-button")).toBeVisible();
  });

  test("has clear logs button", async ({ page }) => {
    await expect(page.getByTestId("clear-button")).toBeVisible();
  });

  test("has export logs button", async ({ page }) => {
    await expect(page.getByTestId("export-logs-btn")).toBeVisible();
  });

  test("can switch to history tab", async ({ page }) => {
    await page.getByTestId("tab-history").click();
    await page.waitForTimeout(500);
    // History tab should show its own filter controls
    await expect(page.getByTestId("history-search").or(
      page.getByTestId("history-filter-instance")
    ).first()).toBeVisible({ timeout: 5000 });
  });

  test("pause button toggles text", async ({ page }) => {
    const pauseBtn = page.getByTestId("pause-button");
    // Initially shows "Pause"
    await expect(pauseBtn).toContainText(/pause/i);
    await pauseBtn.click();
    // After clicking, should show "Resume"
    await expect(pauseBtn).toContainText(/resume/i);
  });

  test("level filter has expected options", async ({ page }) => {
    const select = page.getByTestId("filter-level");
    // Click to open
    const options = select.locator("option");
    // Should have options: All Levels, ERROR, WARN, INFO, DEBUG
    await expect(options).toHaveCount(5);
  });
});
