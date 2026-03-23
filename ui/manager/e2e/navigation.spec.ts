import { test, expect } from "@playwright/test";
import { waitForApp } from "./e2e-helpers";

test.describe("Navigation", () => {
  test("loads dashboard by default", async ({ page }) => {
    await page.goto("/");
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

  test("navigates to packages page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: /packages/i })
      .click();
    await expect(page).toHaveURL(/\/manage\/packages/);
  });

  test("navigates to conversations page via sidebar", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await page
      .getByTestId("sidebar")
      .getByRole("link", { name: /conversations/i })
      .click();
    await expect(page).toHaveURL(/\/manage\/conversations/);
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
