import { test, expect } from "./fixtures";
import { waitForApp, expectHeading } from "./e2e-helpers";

// ═══════════════════════════════════════════════════════════════
// Audit Page
// ═══════════════════════════════════════════════════════════════

test.describe("Audit Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/audit");
    await waitForApp(page);
  });

  test("renders audit heading", async ({ page }) => {
    await expectHeading(page, /audit/i);
  });

  test("shows audit page container", async ({ page }) => {
    await expect(page.getByTestId("audit-page")).toBeVisible();
  });

  test("shows search mode toggle (Agent / Conversation)", async ({ page }) => {
    await expect(page.getByTestId("mode-agent")).toBeVisible();
    await expect(page.getByTestId("mode-conversation")).toBeVisible();
  });

  test("agent mode is default active mode", async ({ page }) => {
    // Agent mode button should be primary-styled by default
    const agentBtn = page.getByTestId("mode-agent");
    await expect(agentBtn).toBeVisible();
  });

  test("shows agent selector in agent mode", async ({ page }) => {
    await expect(page.getByTestId("agent-input")).toBeVisible();
  });

  test("shows version input in agent mode", async ({ page }) => {
    await expect(page.getByTestId("version-input")).toBeVisible();
  });

  test("can switch to conversation mode", async ({ page }) => {
    await page.getByTestId("mode-conversation").click();
    await expect(page.getByTestId("conversation-input")).toBeVisible();
    await expect(page.getByTestId("search-button")).toBeVisible();
  });

  test("conversation mode search button is disabled when empty", async ({
    page,
  }) => {
    await page.getByTestId("mode-conversation").click();
    const searchBtn = page.getByTestId("search-button");
    await expect(searchBtn).toBeDisabled();
  });

  test("conversation mode search button enables with input", async ({
    page,
  }) => {
    await page.getByTestId("mode-conversation").click();
    await page.getByTestId("conversation-input").fill("conv1");
    const searchBtn = page.getByTestId("search-button");
    await expect(searchBtn).toBeEnabled();
  });

  test("export button is visible but disabled when no results", async ({
    page,
  }) => {
    const exportBtn = page.getByTestId("export-btn");
    await expect(exportBtn).toBeVisible();
    await expect(exportBtn).toBeDisabled();
  });
});

// ═══════════════════════════════════════════════════════════════
// GDPR / Privacy Page
// ═══════════════════════════════════════════════════════════════

test.describe("GDPR Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/gdpr");
    await waitForApp(page);
  });

  test("renders GDPR heading", async ({ page }) => {
    await expectHeading(page, /privacy|compliance|gdpr/i);
  });

  test("shows GDPR page container", async ({ page }) => {
    await expect(page.getByTestId("gdpr-page")).toBeVisible();
  });

  test("shows user ID input field", async ({ page }) => {
    await expect(page.getByTestId("gdpr-user-id")).toBeVisible();
  });

  test("shows export data button", async ({ page }) => {
    await expect(page.getByTestId("gdpr-export-btn")).toBeVisible();
  });

  test("shows delete data button", async ({ page }) => {
    await expect(page.getByTestId("gdpr-delete-btn")).toBeVisible();
  });

  test("export button is disabled when no user ID entered", async ({
    page,
  }) => {
    await expect(page.getByTestId("gdpr-export-btn")).toBeDisabled();
  });

  test("delete button is disabled when no user ID entered", async ({
    page,
  }) => {
    await expect(page.getByTestId("gdpr-delete-btn")).toBeDisabled();
  });

  test("buttons enable when user ID is entered", async ({ page }) => {
    await page.getByTestId("gdpr-user-id").fill("user-42");
    await expect(page.getByTestId("gdpr-export-btn")).toBeEnabled();
    await expect(page.getByTestId("gdpr-delete-btn")).toBeEnabled();
  });

  test("shows processing restriction section", async ({ page }) => {
    await expect(
      page.getByTestId("gdpr-restriction-section")
    ).toBeVisible();
  });

  test("shows restrict toggle button", async ({ page }) => {
    await expect(
      page.getByTestId("gdpr-restrict-toggle")
    ).toBeVisible();
  });

  test("shows data protection notice banner", async ({ page }) => {
    await expect(
      page.getByText(/data protection notice/i)
    ).toBeVisible();
  });

  test("shows GDPR article references in subtitle", async ({ page }) => {
    await expect(
      page.getByText(/art\. 17|art\. 15|art\. 18/i).first()
    ).toBeVisible();
  });
});

// ═══════════════════════════════════════════════════════════════
// Variables Page
// ═══════════════════════════════════════════════════════════════

test.describe("Variables Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/variables");
    await waitForApp(page);
  });

  test("renders variables heading", async ({ page }) => {
    await expectHeading(page, /variable/i);
  });

  test("shows variables page container", async ({ page }) => {
    await expect(page.getByTestId("variables-page")).toBeVisible();
  });

  test("shows create variable button", async ({ page }) => {
    await expect(
      page.getByTestId("create-variable-button")
    ).toBeVisible();
  });

  // Rows are addressed by `variable-row-${key}` rather than by their visible
  // text. `getByText("default-model")` also matches the row's copy-reference
  // string (`${eddivar:default-model}`), which trips Playwright strict mode —
  // and the old probes hid that inside `.isVisible().catch(() => false)`, so a
  // strict-mode violation came back as `false` and skipped the test. A testid
  // resolves to exactly one node, which is what AGENTS.md §247 asks for anyway.
  const variableRow = (key: string) => `variable-row-${key}`;

  test("shows variables from MSW mock data", async ({ page }) => {
    // This used to be a bare `test.skip(!visible, "MSW browser worker too
    // slow")` and nothing else, so it could not fail in either direction: data
    // present meant an empty body, data absent meant a skip that reports green.
    // Slow mock data is the bug this page-load test exists to catch, so it is
    // now an assertion with a generous timeout instead of an escape hatch.
    await expect(page.getByTestId(variableRow("default-model"))).toBeVisible({
      timeout: 15_000,
    });
  });

  test("shows variable values", async ({ page }) => {
    const row = page.getByTestId(variableRow("default-model"));
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText("gpt-4.1");
  });

  test("shows search/filter input", async ({ page }) => {
    await expect(page.getByTestId("variables-search")).toBeVisible();
  });

  test("search filters variables", async ({ page }) => {
    await expect(page.getByTestId(variableRow("default-model"))).toBeVisible({
      timeout: 15_000,
    });

    await page.getByTestId("variables-search").fill("rag");

    // Both halves matter: the match appears AND the non-match goes away. Only
    // asserting the match passes against a search box that filters nothing.
    await expect(page.getByTestId(variableRow("rag.chunk-size"))).toBeVisible();
    await expect(page.getByTestId(variableRow("default-model"))).toBeHidden();
  });

  test("create dialog opens on button click", async ({ page }) => {
    await page.getByTestId("create-variable-button").click();
    // Dialog should appear
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible({ timeout: 3000 });
  });

  test("create dialog has required fields", async ({ page }) => {
    await page.getByTestId("create-variable-button").click();
    await expect(page.getByTestId("var-key-input")).toBeVisible();
    await expect(page.getByTestId("var-value-input")).toBeVisible();
  });

  test("create dialog validates key format", async ({ page }) => {
    await page.getByTestId("create-variable-button").click();
    // Enter an invalid key
    await page.getByTestId("var-key-input").fill("invalid key!");
    // Should show validation error
    await expect(page.getByTestId("key-error")).toBeVisible();
  });

  test("shows exportable status for variables", async ({ page }) => {
    // Some variables are exportable, others are not
    // "api.base-url" has exportable: false
    const mainTable = page.locator("table").first();
    await expect(mainTable).toBeVisible();
  });
});

// ═══════════════════════════════════════════════════════════════
// Properties Page (User Data → Properties tab)
// ═══════════════════════════════════════════════════════════════

test.describe("User Data / Properties Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/userdata");
    await waitForApp(page);
  });

  test("renders user data page", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });

  test("shows tab navigation", async ({ page }) => {
    // The user data page has tabs: Properties, Memories, Conversations
    const main = page.locator("main");
    await expect(main).toBeVisible();
  });
});

// ═══════════════════════════════════════════════════════════════
// Coordinator Page
// ═══════════════════════════════════════════════════════════════

test.describe("Coordinator Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/coordinator");
    await waitForApp(page);
  });

  test("renders coordinator page", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });

  test("shows connection status", async ({ page }) => {
    // MSW returns coordinator status with connected: true
    await expect(
      page.getByText(/connected/i).first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("shows queue depth information", async ({ page }) => {
    // MSW returns 12 queue entries
    const main = page.locator("main");
    await expect(main).toBeVisible();
  });
});

// ═══════════════════════════════════════════════════════════════
// Orphans Page
// ═══════════════════════════════════════════════════════════════

test.describe("Orphans Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/orphans");
    await waitForApp(page);
  });

  test("renders orphans page", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });

  test("shows orphan resources from MSW mock data", async ({ page }) => {
    // MSW returns 5 orphan resources
    await expect(
      page.getByText(/orphan/i).first()
    ).toBeVisible({ timeout: 5000 });
  });
});

// ═══════════════════════════════════════════════════════════════
// Secrets Page
// ═══════════════════════════════════════════════════════════════

test.describe("Secrets Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/secrets");
    await waitForApp(page);
  });

  test("renders secrets page", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });

  test("shows secret entries from MSW mock data", async ({ page }) => {
    // MSW returns 8 secrets including "openai-api-key", "anthropic-api-key"
    await expect(
      page.getByText(/openai-api-key/i).first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("shows create secret button", async ({ page }) => {
    await expect(
      page.getByRole("button", { name: /add|create|new secret/i }).first()
    ).toBeVisible();
  });
});
