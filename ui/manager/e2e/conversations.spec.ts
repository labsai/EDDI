import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Conversations Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/conversations");
    await waitForApp(page);
  });

  test("renders conversations heading", async ({ page }) => {
    await expectHeading(page, /conversations/i);
  });

  test("shows conversation table with mock data", async ({ page }) => {
    // MSW returns 2 conversations
    const table = page.locator("table");
    await expect(table).toBeVisible();

    // Table should have rows for the conversations
    const rows = table.locator("tbody tr");
    await expect(rows).toHaveCount(2);
  });

  test("shows state badges in table", async ({ page }) => {
    // First conv is READY (Active), second is ENDED
    // Use .first() to handle potential duplicates
    await expect(page.getByText("Active").first()).toBeVisible();
  });

  test("search input is present", async ({ page }) => {
    await expect(page.getByTestId("conversation-search")).toBeVisible();
  });

  test("state filter buttons are visible", async ({ page }) => {
    // Filter buttons: All, Active, In Progress, Ended, Error
    await expect(
      page.getByRole("button", { name: /^all$/i })
    ).toBeVisible();
  });

  test("clicking conversation navigates to detail", async ({ page }) => {
    const firstConvLink = page.locator("table tbody tr a").first();
    await firstConvLink.click();
    await expect(page).toHaveURL(/\/manage\/conversationview\//);
  });

  test("delete button in table row is present", async ({ page }) => {
    const deleteButtons = page.locator("table tbody tr button");
    await expect(deleteButtons.first()).toBeVisible();
  });
});

test.describe("Conversation Detail", () => {
  test("renders conversation detail with steps", async ({ page }) => {
    await page.goto("/manage/conversationview/conv1");
    await waitForApp(page);

    // Back link should be visible
    await expect(page.getByText(/back to conversations/i)).toBeVisible();
  });

  test("back link navigates to conversations list", async ({ page }) => {
    await page.goto("/manage/conversationview/conv1");
    await waitForApp(page);

    await page.getByText(/back to conversations/i).click();
    await expect(page).toHaveURL(/\/manage\/conversations/);
  });
});
