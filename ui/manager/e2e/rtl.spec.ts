import { test, expect } from "@playwright/test";

test.describe("RTL Layout", () => {
  test("switches to RTL when Arabic is selected", async ({ page }) => {
    await page.goto("/manage");

    // Select Arabic language
    const selector = page.getByTestId("language-selector");
    await selector.selectOption("ar");

    // Verify RTL direction
    const html = page.locator("html");
    await expect(html).toHaveAttribute("dir", "rtl");
    await expect(html).toHaveAttribute("lang", "ar");
  });

  test("switches back to LTR when English is selected", async ({ page }) => {
    await page.goto("/manage");

    // Go to Arabic first
    await page.getByTestId("language-selector").selectOption("ar");
    await expect(page.locator("html")).toHaveAttribute("dir", "rtl");

    // Switch back to English
    await page.getByTestId("language-selector").selectOption("en");
    await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
  });

  test("displays Arabic translations", async ({ page }) => {
    await page.goto("/manage");

    await page.getByTestId("language-selector").selectOption("ar");

    // Check Arabic text is displayed
    await expect(page.getByText("لوحة التحكم")).toBeVisible();
  });
});
