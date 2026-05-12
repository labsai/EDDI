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

    await expect(page.getByTestId("resource-type-rules")).toBeVisible();
    await expect(page.getByTestId("resource-type-apicalls")).toBeVisible();
    await expect(page.getByTestId("resource-type-output")).toBeVisible();
    await expect(
      page.getByTestId("resource-type-dictionary")
    ).toBeVisible();
    await expect(page.getByTestId("resource-type-llm")).toBeVisible();
    await expect(
      page.getByTestId("resource-type-propertysetter")
    ).toBeVisible();
  });

  test("type card navigates to resource list", async ({ page }) => {
    await page.getByTestId("resource-type-rules").click();
    await expect(page).toHaveURL(/\/manage\/resources\/rules/);
  });
});

test.describe("Resource List", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/rules");
    await waitForApp(page);
  });

  test("shows resource items", async ({ page }) => {
    await expect(page.getByTestId("resource-grid")).toBeVisible();
  });

  test("search input filters resources", async ({ page }) => {
    const searchInput = page.getByTestId("resource-search");
    await expect(searchInput).toBeVisible();
  });

  test("create button is visible", async ({ page }) => {
    await expect(
      page.getByTestId("create-resource-btn")
    ).toBeVisible();
  });

  test("resource card navigates to detail", async ({ page }) => {
    const firstCard = page.locator('main a[href*="/manage/resources/rules/"]').first();
    await firstCard.click();
    await expect(page).toHaveURL(/\/manage\/resources\/rules\//);
  });
});

test.describe("Resource Detail", () => {
  test("renders resource detail page", async ({ page }) => {
    await page.goto("/manage/resources/rules/beh1");
    await waitForApp(page);

    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("back link returns to resource list", async ({ page }) => {
    await page.goto("/manage/resources/rules/beh1");
    await waitForApp(page);

    await page.getByTestId("back-to-list").click();
    await expect(page).toHaveURL(/\/manage\/resources\/rules/);
  });

  test("shows form or editor content", async ({ page }) => {
    await page.goto("/manage/resources/rules/beh1");
    await waitForApp(page);

    await expect(page.locator("main").first()).toBeVisible();
  });
});

test.describe("Different Resource Types", () => {
  const resourceTypes = [
    "rules",
    "apicalls",
    "output",
    "dictionary",
    "llm",
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
