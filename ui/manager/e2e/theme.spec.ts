import { test, expect } from "@playwright/test";

test.describe("Theme", () => {
  test("toggles dark mode", async ({ page }) => {
    await page.goto("/manage");

    // Click dark mode button
    await page.getByTestId("theme-dark").click();

    // Verify dark class is applied
    const html = page.locator("html");
    await expect(html).toHaveClass(/dark/);
  });

  test("dark mode persists across reload", async ({ page }) => {
    await page.goto("/manage");

    // Set dark mode
    await page.getByTestId("theme-dark").click();
    await expect(page.locator("html")).toHaveClass(/dark/);

    // Reload and check persistence
    await page.reload();
    await expect(page.locator("html")).toHaveClass(/dark/);
  });

  test("toggles back to light mode", async ({ page }) => {
    await page.goto("/manage");

    await page.getByTestId("theme-dark").click();
    await expect(page.locator("html")).toHaveClass(/dark/);

    await page.getByTestId("theme-light").click();
    await expect(page.locator("html")).toHaveClass(/light/);
    await expect(page.locator("html")).not.toHaveClass(/dark/);
  });
});
