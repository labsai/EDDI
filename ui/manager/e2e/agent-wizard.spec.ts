import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Agent Wizard", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/agents/wizard");
    await waitForApp(page);
  });

  test("renders wizard heading", async ({ page }) => {
    await expectHeading(page, /wizard|setup|create/i);
  });

  test("shows wizard step indicators", async ({ page }) => {
    const steps = page.getByTestId("wizard-steps");
    await expect(steps).toBeVisible();
  });

  test("step 1 — shows agent type selection grid", async ({ page }) => {
    const typeGrid = page.getByTestId("type-grid");
    await expect(typeGrid).toBeVisible();
  });

  test("step 1 — can select standard agent type", async ({ page }) => {
    const standardType = page.getByTestId("type-standard");
    await expect(standardType).toBeVisible();
    await standardType.click();
    // Type is selected but we stay on step 1 until Next is clicked
    await expect(page.getByTestId("wizard-steps")).toBeVisible();
  });

  test("step 2 — shows name and description fields after clicking Next", async ({
    page,
  }) => {
    // Select standard agent type
    await page.getByTestId("type-standard").click();
    // Click Next to advance to step 2
    await page.getByTestId("wizard-next").click();
    // Look for name input field
    const nameInput = page.getByTestId("wizard-agent-name");
    await expect(nameInput).toBeVisible({ timeout: 5000 });
  });

  test("step 2 — can fill in agent name", async ({ page }) => {
    // Navigate to step 2
    await page.getByTestId("type-standard").click();
    await page.getByTestId("wizard-next").click();

    const nameInput = page.getByTestId("wizard-agent-name");
    await expect(nameInput).toBeVisible({ timeout: 5000 });
    await nameInput.fill("Test E2E Agent");
    await expect(nameInput).toHaveValue("Test E2E Agent");
  });

  test("back button returns to previous step", async ({ page }) => {
    // Navigate to step 2
    await page.getByTestId("type-standard").click();
    await page.getByTestId("wizard-next").click();
    await expect(page.getByTestId("wizard-agent-name")).toBeVisible({
      timeout: 5000,
    });

    // Click back
    const backBtn = page.getByTestId("wizard-back");
    await expect(backBtn).toBeVisible();
    await backBtn.click();

    // Should return to type selection
    await expect(page.getByTestId("type-grid")).toBeVisible({ timeout: 5000 });
  });

  test("shows both Standard and API agent types", async ({ page }) => {
    await expect(page.getByTestId("type-standard")).toBeVisible();
    await expect(page.getByTestId("type-api")).toBeVisible();
  });

  test("wizard has cancel action", async ({ page }) => {
    const cancelLink = page.getByRole("link", { name: /back to agents/i });
    await expect(cancelLink).toBeVisible();
  });
});
