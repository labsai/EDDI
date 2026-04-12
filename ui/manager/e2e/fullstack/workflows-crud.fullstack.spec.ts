import { test, expect } from "@playwright/test";
import {
  waitForFullStack,
  navigateTo,
  API_BASE,
  waitForBackend,
} from "./fullstack-helpers";
import {
  extractIdFromLocation,
  cleanupResource,
} from "../integration/integration-helpers";

/**
 * Workflows (packages) page tested with real backend data.
 * Creates packages via API, then verifies the UI list and detail
 * pages render correctly with real data.
 */
test.describe("Workflows — Full Stack", () => {
  test.describe.configure({ timeout: 120_000, mode: "serial" });

  const createdPackages: { id: string; version: number }[] = [];

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test.afterAll(async ({ request }) => {
    for (const pkg of createdPackages) {
      await cleanupResource(
        request,
        "packagestore/packages",
        pkg.id,
        pkg.version
      );
    }
  });

  test("workflows page renders with real data", async ({ page, request }) => {
    // Create a package so there's something to show
    const createRes = await request.post(`${API_BASE}/packagestore/packages`, {
      data: { packageExtensions: [] },
    });
    expect(createRes.status()).toBe(201);
    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!
    );
    createdPackages.push({ id, version });

    await waitForFullStack(page, request, "/manage/workflows", {
      skipHealthCheck: true,
    });

    // Heading
    await expect(page.locator("h1")).toContainText(/workflow|package/i);
  });

  test("package cards are visible", async ({ page }) => {
    await navigateTo(page, "/manage/workflows");

    // At least one package card link should be visible
    const packageLinks = page.locator(
      'main a[href*="/manage/workflowview/"]'
    );
    await expect(packageLinks.first()).toBeVisible({ timeout: 10_000 });
  });

  test("search input is functional", async ({ page }) => {
    await navigateTo(page, "/manage/workflows");

    const searchInput = page.locator('main input[type="text"]').first();
    await expect(searchInput).toBeVisible();
    await searchInput.fill("test");
    await expect(searchInput).toHaveValue("test");
  });

  test("package card navigates to detail", async ({ page }) => {
    await navigateTo(page, "/manage/workflows");

    const firstCard = page
      .locator('main a[href*="/manage/workflowview/"]')
      .first();
    await firstCard.click();
    await expect(page).toHaveURL(/\/manage\/workflowview\//);
  });

  test("workflow detail shows version selector", async ({ page }) => {
    const pkgId = createdPackages[0]?.id;
    if (!pkgId) {
      test.skip();
      return;
    }

    await navigateTo(page, `/manage/workflowview/${pkgId}`);

    // Version badge or picker should be visible
    const badge = page.getByTestId("version-badge");
    const picker = page.getByTestId("version-picker");
    const either = badge.or(picker);
    await expect(either.first()).toBeVisible({ timeout: 10_000 });
  });

  test("workflow detail shows back link", async ({ page }) => {
    const pkgId = createdPackages[0]?.id;
    if (!pkgId) {
      test.skip();
      return;
    }

    await navigateTo(page, `/manage/workflowview/${pkgId}`);

    await expect(page.getByText(/back to/i)).toBeVisible();
  });

  test("back link navigates to workflows list", async ({ page }) => {
    const pkgId = createdPackages[0]?.id;
    if (!pkgId) {
      test.skip();
      return;
    }

    await navigateTo(page, `/manage/workflowview/${pkgId}`);
    await page.getByText(/back to/i).click();
    await expect(page).toHaveURL(/\/manage\/workflows/);
  });
});
