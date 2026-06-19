import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Group Wizard", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/groups/wizard");
    await waitForApp(page);
  });

  test("renders wizard heading", async ({ page }) => {
    await expectHeading(page, /create|new group|wizard/i);
  });

  test("shows wizard step indicators", async ({ page }) => {
    const steps = page.getByTestId("group-wizard-steps");
    await expect(steps).toBeVisible();
  });

  test("step 1 — shows template selection grid", async ({ page }) => {
    // Step 1 is template selection, not style selection
    await expect(
      page.getByTestId("template-grid")
    ).toBeVisible({ timeout: 5000 });
  });

  test("step 1 — can select a template", async ({ page }) => {
    // Click the first template card — this auto-advances to step 2 (config)
    const firstTemplate = page.getByTestId("template-grid").locator("button").first();
    await expect(firstTemplate).toBeVisible({ timeout: 5000 });
    await firstTemplate.click();

    // After selecting a template, we should be on step 2 (config) with gw-name visible
    await expect(page.getByTestId("gw-name")).toBeVisible({ timeout: 5000 });
  });

  test("step 1 — can skip to blank group", async ({ page }) => {
    const blankBtn = page.getByTestId("template-blank");
    await expect(blankBtn).toBeVisible({ timeout: 5000 });
    await blankBtn.click();
    // Should advance to step 2 (config)
    await expect(page.getByTestId("gw-name")).toBeVisible({ timeout: 5000 });
  });

  test("step 2 — shows style selection buttons", async ({ page }) => {
    // Select a template to get to step 2
    await page.getByTestId("template-grid").locator("button").first().click();
    await expect(page.getByTestId("gw-name")).toBeVisible({ timeout: 5000 });

    // Style selection is on step 2
    await expect(
      page.getByTestId("gw-style-ROUND_TABLE")
    ).toBeVisible({ timeout: 5000 });
  });

  test("step 2 — can change discussion style", async ({ page }) => {
    await page.getByTestId("template-grid").locator("button").first().click();
    await expect(page.getByTestId("gw-name")).toBeVisible({ timeout: 5000 });

    // Click a different style
    const debateStyle = page.getByTestId("gw-style-DEBATE");
    await expect(debateStyle).toBeVisible();
    await debateStyle.click();
    await expect(debateStyle).toBeVisible();
  });

  test("back button returns to template step", async ({ page }) => {
    // Select a template to advance to step 2
    await page.getByTestId("template-grid").locator("button").first().click();
    await expect(page.getByTestId("gw-name")).toBeVisible({ timeout: 5000 });

    // Click back
    const backBtn = page.getByTestId("group-wizard-back");
    await expect(backBtn).toBeVisible();
    await backBtn.click();

    // Should return to template selection
    await expect(page.getByTestId("template-grid")).toBeVisible({ timeout: 5000 });
  });

  test("wizard has cancel action", async ({ page }) => {
    const cancelLink = page.getByRole("link", { name: /back to groups/i });
    await expect(cancelLink).toBeVisible();
  });
});

test.describe("Groups List Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/groups");
    await waitForApp(page);
  });

  test("renders groups heading", async ({ page }) => {
    await expectHeading(page, /group/i);
  });

  test("shows group cards from MSW mock data", async ({ page }) => {
    await expect(
      page.getByText("Product Review Panel").first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("new group button is visible", async ({ page }) => {
    await expect(page.getByTestId("create-group-btn")).toBeVisible();
  });

  test("clicking a group navigates to group detail", async ({ page }) => {
    const groupLink = page.locator("main a[href*='/manage/groups/']").first();
    await expect(groupLink).toBeVisible({ timeout: 5000 });
    await groupLink.click();
    await page.waitForURL(/\/manage\/groups\//, { timeout: 10000 });
  });
});

test.describe("Group Detail Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/groups/grp1?version=1");
    await waitForApp(page);
  });

  test("shows group name in heading", async ({ page }) => {
    await expect(
      page.getByText("Product Review Panel").first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("shows group members", async ({ page }) => {
    await expect(
      page.getByText(/Support Agent|FAQ Agent/i).first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("shows main content area", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });
});
