import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Workflow Detail Page", () => {
  test.beforeEach(async ({ page }) => {
    // MSW has wf1 at version 2
    await page.goto("/manage/workflows/wf1?version=2");
    await waitForApp(page);
  });

  test("renders workflow detail page", async ({ page }) => {
    // The heading may be the workflow name or just "Workflow"
    const heading = page.locator("h1");
    await expect(heading).toBeVisible({ timeout: 15000 });
  });

  test("shows main content area", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible({ timeout: 10000 });
  });
});

test.describe("Workflows List Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/workflows");
    await waitForApp(page);
  });

  test("renders workflows heading", async ({ page }) => {
    await expectHeading(page, /workflow/i);
  });

  test("create workflow button is visible", async ({ page }) => {
    const createBtn = page.getByTestId("create-workflow-btn").or(
      page.getByRole("button", { name: /create|new workflow/i })
    );
    await expect(createBtn.first()).toBeVisible();
  });
});
