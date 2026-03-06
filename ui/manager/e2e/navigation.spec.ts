import { test, expect } from "@playwright/test";

test.describe("Navigation", () => {
  test("loads dashboard by default", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/manage/);
    await expect(page.getByText("Dashboard")).toBeVisible();
  });

  test("navigates to bots page", async ({ page }) => {
    await page.goto("/manage");
    await page.getByRole("link", { name: /bots/i }).click();
    await expect(page).toHaveURL(/\/manage\/bots/);
  });

  test("navigates to packages page", async ({ page }) => {
    await page.goto("/manage");
    await page.getByRole("link", { name: /packages/i }).click();
    await expect(page).toHaveURL(/\/manage\/packages/);
  });

  test("navigates to conversations page", async ({ page }) => {
    await page.goto("/manage");
    await page.getByRole("link", { name: /conversations/i }).click();
    await expect(page).toHaveURL(/\/manage\/conversations/);
  });

  test("navigates to resources page", async ({ page }) => {
    await page.goto("/manage");
    await page.getByRole("link", { name: /resources/i }).click();
    await expect(page).toHaveURL(/\/manage\/resources/);
  });
});
