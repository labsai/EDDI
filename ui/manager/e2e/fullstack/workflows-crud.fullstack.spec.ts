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

  /**
   * The package every test below needs, created once.
   *
   * This used to be created inside the first test, so three later tests opened
   * with `if (!pkgId) { test.skip(); return; }` — meaning a broken create flow
   * silently skipped the tests that would have shown its blast radius, and the
   * run still reported green. Creating it here makes a create failure fail the
   * whole describe at setup, where it is legible.
   */
  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);

    const createRes = await request.post(`${API_BASE}/workflowstore/workflows`, {
      data: { workflowSteps: [] },
    });
    expect(
      createRes.status(),
      "could not create the fixture package the Workflows full-stack suite needs",
    ).toBe(201);

    const { id, version } = extractIdFromLocation(
      createRes.headers()["location"]!,
    );
    createdPackages.push({ id, version });
  });

  test.afterAll(async ({ request }) => {
    for (const pkg of createdPackages) {
      await cleanupResource(
        request,
        "workflowstore/workflows",
        pkg.id,
        pkg.version
      );
    }
  });

  test("workflows page renders with real data", async ({ page, request }) => {
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
    const pkgId = createdPackages[0]!.id;

    await navigateTo(page, `/manage/workflowview/${pkgId}`);

    // Version badge or picker should be visible
    const badge = page.getByTestId("version-badge");
    const picker = page.getByTestId("version-picker");
    const either = badge.or(picker);
    await expect(either.first()).toBeVisible({ timeout: 10_000 });
  });

  test("workflow detail shows back link", async ({ page }) => {
    const pkgId = createdPackages[0]!.id;

    await navigateTo(page, `/manage/workflowview/${pkgId}`);

    await expect(page.getByText(/back to/i)).toBeVisible();
  });

  test("back link navigates to workflows list", async ({ page }) => {
    const pkgId = createdPackages[0]!.id;

    await navigateTo(page, `/manage/workflowview/${pkgId}`);
    await page.getByText(/back to/i).click();
    await expect(page).toHaveURL(/\/manage\/workflows/);
  });
});
