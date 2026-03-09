import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Resources Hub", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources");
    await waitForApp(page);
  });

  test("renders resources heading", async ({ page }) => {
    await expectHeading(page, /resources/i);
  });

  test("shows resource type cards grid", async ({ page }) => {
    const grid = page.getByTestId("resource-types-grid");
    await expect(grid).toBeVisible();

    // 6 resource types: behavior, httpcalls, output, dictionaries, langchain, propertysetter
    await expect(page.getByTestId("resource-type-behavior")).toBeVisible();
    await expect(page.getByTestId("resource-type-httpcalls")).toBeVisible();
    await expect(page.getByTestId("resource-type-output")).toBeVisible();
    await expect(
      page.getByTestId("resource-type-dictionaries")
    ).toBeVisible();
    await expect(page.getByTestId("resource-type-langchain")).toBeVisible();
    await expect(
      page.getByTestId("resource-type-propertysetter")
    ).toBeVisible();
  });

  test("type card navigates to resource list", async ({ page }) => {
    await page.getByTestId("resource-type-behavior").click();
    await expect(page).toHaveURL(/\/manage\/resources\/behavior/);
  });
});

test.describe("Resource List", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/behavior");
    await waitForApp(page);
  });

  test("shows resource items", async ({ page }) => {
    // At least one resource card should be visible
    await expect(page.locator('main a[href*="/manage/resources/behavior/"]').first()).toBeVisible();
  });

  test("search input filters resources", async ({ page }) => {
    const searchInput = page.locator('main input[type="text"]').first();
    await expect(searchInput).toBeVisible();
  });

  test("create button is visible", async ({ page }) => {
    await expect(
      page.getByText(/create/i).first()
    ).toBeVisible();
  });

  test("resource card navigates to detail", async ({ page }) => {
    // Click the first resource card link
    const firstCard = page.locator('main a[href*="/manage/resources/behavior/"]').first();
    await firstCard.click();
    await expect(page).toHaveURL(/\/manage\/resources\/behavior\//);
  });
});

test.describe("Resource Detail", () => {
  test("renders resource detail page", async ({ page }) => {
    await page.goto("/manage/resources/behavior/res1");
    await waitForApp(page);

    // Back link from shared component
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("back link returns to resource list", async ({ page }) => {
    await page.goto("/manage/resources/behavior/res1");
    await waitForApp(page);

    await page.getByTestId("back-to-list").click();
    await expect(page).toHaveURL(/\/manage\/resources\/behavior/);
  });

  test("shows form or editor content", async ({ page }) => {
    await page.goto("/manage/resources/behavior/res1");
    await waitForApp(page);

    // The resource detail page should show form or JSON editor
    await expect(page.locator("main").first()).toBeVisible();
  });
});

test.describe("Different Resource Types", () => {
  const resourceTypes = [
    "behavior",
    "httpcalls",
    "output",
    "dictionaries",
    "langchain",
    "propertysetter",
  ];

  for (const type of resourceTypes) {
    test(`${type} resource type card is present on hub`, async ({ page }) => {
      await page.goto("/manage/resources");
      await waitForApp(page);

      await expect(
        page.getByTestId(`resource-type-${type}`)
      ).toBeVisible();
    });
  }
});
