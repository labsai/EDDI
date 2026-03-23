import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Chat Panel", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/chat");
    await waitForApp(page);
  });

  test("renders chat heading", async ({ page }) => {
    await expectHeading(page, /chat/i);
  });

  test("shows agent selector", async ({ page }) => {
    const agentSelector = page.getByTestId("agent-selector");
    await expect(agentSelector).toBeVisible();
  });

  test("streaming toggle is present", async ({ page }) => {
    await expect(page.getByTestId("streaming-toggle")).toBeVisible();
  });

  test("agent selector is clickable", async ({ page }) => {
    // Agent selector is a Radix Select (rendered as <button>)
    const agentSelector = page.getByTestId("agent-selector");
    await agentSelector.click();

    // After clicking, a dropdown with agent options should appear
    // Radix Select renders options in a portal
    await expect(
      page.getByText("Support Agent").first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("chat input appears after selecting a agent", async ({ page }) => {
    // Click agent selector to open dropdown
    const agentSelector = page.getByTestId("agent-selector");
    await agentSelector.click();

    // Select a agent from the dropdown
    await page.getByText("Support Agent").first().click();

    // Wait for conversation to be created, then check for input
    const chatInput = page.getByTestId("chat-input");
    await expect(chatInput).toBeVisible({ timeout: 15000 });
  });
});
