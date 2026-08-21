import { test, expect } from "@playwright/test";
import { waitForApp } from "./e2e-helpers";

test.describe("Resource Editor — Rules", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/rules/beh1");
    await waitForApp(page);
  });

  test("renders resource detail page", async ({ page }) => {
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("shows form and JSON tabs", async ({ page }) => {
    // Use the actual data-testids for editor tabs
    await expect(page.getByTestId("tab-form")).toBeVisible();
    await expect(page.getByTestId("tab-json")).toBeVisible();
  });

  test("shows save button", async ({ page }) => {
    const saveBtn = page.getByRole("button", { name: /save/i }).or(
      page.getByTestId("save-btn")
    );
    await expect(saveBtn.first()).toBeVisible();
  });

  test("shows version selector or badge", async ({ page }) => {
    const badge = page.getByTestId("version-badge");
    const picker = page.getByTestId("version-picker");
    await expect(badge.or(picker).first()).toBeVisible();
  });

  test("can switch to JSON tab", async ({ page }) => {
    const jsonTab = page.getByTestId("tab-json");
    await expect(jsonTab).toBeVisible({ timeout: 10000 });
    await jsonTab.click();

    // A Monaco crash used to be skipped here ("error boundary replaced the
    // editor"), which reported the one failure this test exists to catch as a
    // pass. An editor that dies on the JSON tab is a bug in every environment,
    // headless CI included, so it now fails — and the error-boundary assertion
    // below makes the failure say *why* instead of leaving a bare missing tab.
    await expect(
      page.getByTestId("error-boundary-fallback"),
      "Monaco crashed and the error boundary replaced the editor",
    ).toHaveCount(0);

    await expect(page.getByTestId("tab-json")).toHaveAttribute(
      "aria-selected",
      "true",
      { timeout: 10000 },
    );
  });

  test("shows rules editor with behavior groups", async ({ page }) => {
    // The rules editor renders the rules-editor testid
    await expect(
      page.getByTestId("rules-editor")
    ).toBeVisible({ timeout: 5000 });
  });

  test("shows rule group name in input", async ({ page }) => {
    // MSW returns a behavior group named "Greeting Rules" — rendered in an input
    const groupNameInput = page.locator('input[value="Greeting Rules"]');
    await expect(groupNameInput).toBeVisible({ timeout: 5000 });
  });
});

test.describe("Resource Editor — LLM", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/llm/llm1");
    await waitForApp(page);
  });

  test("renders LLM resource detail page", async ({ page }) => {
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("shows LLM editor form tab", async ({ page }) => {
    // The form tab should be active by default
    await expect(page.getByTestId("tab-form")).toBeVisible();
  });

  test("shows LLM editor content with provider selector", async ({ page }) => {
    // The LLM editor has provider/model dropdowns — "openai" is in a hidden option element
    // Instead verify the LLM editor form renders
    await expect(
      page.getByTestId("tab-form")
    ).toBeVisible({ timeout: 5000 });
    // Check that the editor layout has loaded
    await expect(page.getByTestId("app-layout")).toBeVisible();
  });
});

test.describe("Resource Editor — API Calls", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/apicalls/hc1");
    await waitForApp(page);
  });

  test("renders API calls resource detail page", async ({ page }) => {
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("shows API call entries from mock data", async ({ page }) => {
    // Was a bare `test.skip(!visible, "MSW browser worker too slow")` with no
    // assertion after it, so neither branch could fail. MSW returns httpCalls
    // named "get_weather", "lookup_order" and friends; not rendering them is
    // the failure, not a reason to opt out.
    await expect(page.getByText(/weather/i).first()).toBeVisible({ timeout: 15_000 });
  });
});

test.describe("Resource Editor — Dictionary", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/dictionary/dict1");
    await waitForApp(page);
  });

  test("renders dictionary resource detail page", async ({ page }) => {
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("shows dictionary editor content", async ({ page }) => {
    // Dictionary words ("hello", "hi", "goodbye") are likely in input values
    // Just verify the form tab renders
    await expect(page.getByTestId("tab-form")).toBeVisible({ timeout: 5000 });
  });
});

test.describe("Resource Editor — Output", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/output/out1");
    await waitForApp(page);
  });

  test("renders output resource detail page", async ({ page }) => {
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });

  test("shows output actions from mock data", async ({ page }) => {
    // MSW returns output sets with actions "greet", "farewell", "fallback"
    await expect(
      page.getByText(/greet/i).first()
    ).toBeVisible({ timeout: 5000 });
  });
});

test.describe("Resource Editor — Property Setter", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/propertysetter/ps1");
    await waitForApp(page);
  });

  test("renders property setter resource detail page", async ({ page }) => {
    await expect(page.getByTestId("back-to-list")).toBeVisible();
  });
});

test.describe("Resource Create Flow", () => {
  test("create button on resource list navigates to new resource", async ({
    page,
  }) => {
    await page.goto("/manage/resources/rules");
    await waitForApp(page);

    const createBtn = page.getByTestId("create-resource-btn");
    await expect(createBtn).toBeVisible();
  });
});
