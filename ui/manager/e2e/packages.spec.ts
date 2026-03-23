import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Workflows Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/packages");
    await waitForApp(page);
  });

  test("renders packages heading", async ({ page }) => {
    await expectHeading(page, /packages/i);
  });

  test("shows package cards with mock data", async ({ page }) => {
    // MSW returns 2 packages
    await expect(page.getByText("Support Workflow")).toBeVisible();
    await expect(page.getByText("FAQ Workflow")).toBeVisible();
  });

  test("search input is functional", async ({ page }) => {
    const searchInput = page.locator('main input[type="text"]').first();
    await searchInput.fill("FAQ");
    await expect(searchInput).toHaveValue("FAQ");
  });

  test("create package button is visible", async ({ page }) => {
    await expect(
      page.getByText(/create package/i).first()
    ).toBeVisible();
  });

  test("package card navigates to package detail", async ({ page }) => {
    await page.getByText("Support Workflow").click();
    await expect(page).toHaveURL(/\/manage\/packageview\//);
  });
});

test.describe("Workflow Detail", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/packageview/pkg1");
    await waitForApp(page);
  });

  test("shows back link to packages list", async ({ page }) => {
    await expect(page.getByText(/back to packages/i)).toBeVisible();
  });

  test("shows version selector", async ({ page }) => {
    // VersionSelect renders as version-badge (single ver) or version-picker (multi ver)
    const badge = page.getByTestId("version-badge");
    const picker = page.getByTestId("version-picker");
    const either = badge.or(picker);
    await expect(either.first()).toBeVisible();
  });

  test("shows add extension button", async ({ page }) => {
    await expect(page.getByText(/add extension/i).first()).toBeVisible();
  });

  test("shows raw configuration section", async ({ page }) => {
    await expect(page.getByText(/raw configuration/i)).toBeVisible();
  });

  test("back link navigates to packages list", async ({ page }) => {
    await page.getByText(/back to packages/i).click();
    await expect(page).toHaveURL(/\/manage\/packages/);
  });
});
