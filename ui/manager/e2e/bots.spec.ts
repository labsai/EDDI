import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Agents Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/agents");
    await waitForApp(page);
  });

  test("renders agents heading", async ({ page }) => {
    await expectHeading(page, /agents/i);
  });

  test("shows agent cards with mock data", async ({ page }) => {
    // MSW returns 2 agents: "Support Agent" and "FAQ Agent"
    await expect(page.getByText("Support Agent")).toBeVisible();
    await expect(page.getByText("FAQ Agent")).toBeVisible();
  });

  test("search input is functional", async ({ page }) => {
    // Find the search input and verify it works
    const searchInput = page.locator('main input[type="text"]').first();
    await searchInput.fill("Support");
    await expect(searchInput).toHaveValue("Support");
  });

  test("new agent button is visible", async ({ page }) => {
    await expect(
      page.getByText(/New Agent/i).first()
    ).toBeVisible();
  });

  test("agent card click navigates to agent detail", async ({ page }) => {
    await page.getByText("Support Agent").click();
    await expect(page).toHaveURL(/\/manage\/agentview\//);
  });

  test("import button is visible", async ({ page }) => {
    await expect(
      page.getByText(/import/i).first()
    ).toBeVisible();
  });
});

test.describe("Agent Wizard", () => {
  test("wizard page loads", async ({ page }) => {
    await page.goto("/manage/agents/wizard");
    await waitForApp(page);

    await expect(page.locator("h1")).toContainText(/wizard|create/i);
  });
});
