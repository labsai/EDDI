import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/*
 * Knowledge-base ingestion and failed chat turns, against the MSW mock backend.
 *
 * MSW answers from a service worker, so `page.route` never sees these requests
 * (see workspaces.spec.ts). The seams live in the mock handlers instead:
 * `src/test/mocks/handlers.ts` answers a `replace=true` ingestion with a
 * warning, lists a sitemap on the mocked web source, and fails any chat turn
 * containing TASK_FAILURE_TRIGGER. The strings below mirror those constants.
 */
const TASK_FAILURE_TRIGGER = "trigger a task failure";
const REPLACE_WARNING = "previous version is still retrievable (mock)";
const MOCKED_SITEMAP = "https://example.com/docs/sitemap.xml";

test.describe("Knowledge base — ingestion", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/resources/rag/rag1");
    await waitForApp(page);
  });

  test("a website source shows its sitemaps, and takes more", async ({ page }) => {
    await page.getByRole("button", { name: /ingestion sources/i }).click();
    await page.getByTestId("ingestion-source-0-toggle").click();

    const sitemaps = page.getByTestId("ingestion-source-0-sitemap-urls");
    await expect(sitemaps).toHaveValue(MOCKED_SITEMAP);

    await sitemaps.fill(`${MOCKED_SITEMAP}, https://example.com/blog/sitemap.xml.gz`);
    await sitemaps.blur();
    await expect(sitemaps).toHaveValue(`${MOCKED_SITEMAP}, https://example.com/blog/sitemap.xml.gz`);
  });

  test("dropping a file with 'replace' ticked asks for replacement, and shows the store's caveat", async ({ page }) => {
    await page.getByRole("button", { name: /document ingestion/i }).click();
    const panel = page.getByTestId("ingestion-panel");
    await panel.getByTestId("ingest-replace-same-name").check();

    await panel.locator('input[type="file"]').setInputFiles({
      name: "pricing.md",
      mimeType: "text/markdown",
      buffer: Buffer.from("# Pricing\n\nThe plan costs 12 EUR a month."),
    });

    await expect(panel.getByText("pricing.md")).toBeVisible();
    await expect(panel.getByText("completed")).toBeVisible({ timeout: 10000 });
    // The mock returns this only when the request carried replace=true.
    await expect(panel.getByRole("status")).toContainText(REPLACE_WARNING);
  });

  test("without 'replace', a file is ingested with no caveat", async ({ page }) => {
    await page.getByRole("button", { name: /document ingestion/i }).click();
    const panel = page.getByTestId("ingestion-panel");

    await panel.locator('input[type="file"]').setInputFiles({
      name: "faq.md",
      mimeType: "text/markdown",
      buffer: Buffer.from("# FAQ"),
    });

    await expect(panel.getByText("completed")).toBeVisible({ timeout: 10000 });
    await expect(panel.getByRole("status")).toHaveCount(0);
  });
});

for (const streaming of [false, true]) {
  test.describe(`Chat — a failed turn says why (${streaming ? "streaming" : "non-streaming"})`, () => {
    test.beforeEach(async ({ page }) => {
      await page.addInitScript((enabled) => {
        localStorage.setItem("eddi-chat-streaming", String(enabled));
      }, streaming);
      await page.goto("/manage/chat");
      await waitForApp(page);
      await page.getByTestId("agent-selector").click();
      await page.getByText("Support Agent").first().click();
    });

    test("shows the backend's reason instead of an empty reply", async ({ page }) => {
      const input = page.getByTestId("chat-input");
      await expect(input).toBeVisible({ timeout: 15000 });
      await input.fill(`Please ${TASK_FAILURE_TRIGGER}`);
      await input.press("Enter");

      // Markdown renders the backticks as <code>, so the sentence spans elements:
      // match the paragraph, not a text node.
      const notice = page.locator("p").filter({ hasText: "is deprecated for this model." });
      await expect(notice).toBeVisible({ timeout: 10000 });
      await expect(notice).toContainText("⚠️ Task 'eddi://ai.labs.llm' failed:");
      await expect(notice).toContainText("temperature");
    });
  });
}
