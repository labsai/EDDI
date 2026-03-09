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

  test("shows bot selector", async ({ page }) => {
    const botSelector = page.getByTestId("bot-selector");
    await expect(botSelector).toBeVisible();
  });

  test("streaming toggle is present", async ({ page }) => {
    await expect(page.getByTestId("streaming-toggle")).toBeVisible();
  });

  test("bot selector is clickable", async ({ page }) => {
    // Bot selector is a Radix Select (rendered as <button>)
    const botSelector = page.getByTestId("bot-selector");
    await botSelector.click();

    // After clicking, a dropdown with bot options should appear
    // Radix Select renders options in a portal
    await expect(
      page.getByText("Support Bot").first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("chat input appears after selecting a bot", async ({ page }) => {
    // Click bot selector to open dropdown
    const botSelector = page.getByTestId("bot-selector");
    await botSelector.click();

    // Select a bot from the dropdown
    await page.getByText("Support Bot").first().click();

    // Wait for conversation to be created, then check for input
    const chatInput = page.getByTestId("chat-input");
    await expect(chatInput).toBeVisible({ timeout: 15000 });
  });
});
