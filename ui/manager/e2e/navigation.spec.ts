import { test, expect } from "@playwright/test";
import { waitForApp } from "./e2e-helpers";

test.describe("Navigation", () => {
  test("loads dashboard by default", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await expect(page).toHaveURL(/\/manage/);
    // Use heading instead of getByText to avoid matching sidebar "Dashboard"
    await expect(page.locator("h1")).toContainText("Dashboard");
  });

  test("navigates to agents page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: /agents/i })
      .click();
    await expect(page).toHaveURL(/\/manage\/agents/);
  });

  test("navigates to workflows page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: /workflows/i })
      .click();
    await expect(page).toHaveURL(/\/manage\/workflows/);
  });

  test("navigates to conversations page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    // Exact match: the sidebar also has an "Active Conversations" link, so a
    // /conversations/i regex resolves to two elements and trips strict mode.
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: "Conversations", exact: true })
      .click();
    await expect(page).toHaveURL(/\/manage\/conversations/);
  });

  test("navigates to active conversations page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: "Active Conversations", exact: true })
      .click();
    await expect(page).toHaveURL(/\/manage\/conversations\/monitoring/);
  });

  test("navigates to resources page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: /resources/i })
      .click();
    await expect(page).toHaveURL(/\/manage\/resources/);
  });

  test("navigates to chat page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: /chat/i })
      .click();
    await expect(page).toHaveURL(/\/manage\/chat/);
  });
});
