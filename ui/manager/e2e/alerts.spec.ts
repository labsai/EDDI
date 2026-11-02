import { test, expect } from "./fixtures";
import { waitForApp, expectHeading } from "./e2e-helpers";

/**
 * Alerts page against the MSW mocks (`src/test/mocks/alert-handlers.ts`): create a
 * rule, acknowledge an alert in the inbox, and the sidebar badge.
 */
test.describe("Alerts Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/alerts");
    await waitForApp(page);
  });

  test("opens on the inbox and acknowledges an alert", async ({ page }) => {
    await expectHeading(page, /alerts/i);
    await expect(page.getByTestId("alert-event-row-alert-1")).toBeVisible();
    await page.getByTestId("alert-acknowledge-alert-1").click();
    await expect(page.getByTestId("alert-event-row-alert-1")).toHaveCount(0);
    await expect(page.getByTestId("alert-event-row-alert-2")).toBeVisible();
  });

  test("creates a rule from the Rules tab", async ({ page }) => {
    await page.getByTestId("alerts-tab-rules").click();
    await expect(page.getByTestId("alert-rule-row-rule-ratings")).toBeVisible();
    await page.getByTestId("alerts-new-rule").click();
    await page.getByTestId("alert-rule-name").fill("E2E low ratings");
    await page.getByTestId("alert-rule-add-target").click();
    await page.getByTestId("alert-rule-target-channel-0").fill("C0E2E1234");
    await page.getByTestId("alert-rule-save").click();
    await expect(page.getByTestId("alert-rules-list")).toContainText("E2E low ratings");
  });

  test("builds an output-match rule with the condition builder", async ({ page }) => {
    await page.getByTestId("alerts-tab-rules").click();
    await page.getByTestId("alerts-new-rule").click();
    await page.getByTestId("alert-rule-name").fill("E2E escalations");
    await page.getByTestId("alert-rule-source").selectOption("OUTPUT_MATCH");
    await expect(page.getByTestId("output-match-builder")).toBeVisible();
    await page.getByTestId("output-match-path").fill("a.*");
    await expect(page.getByTestId("output-match-path-error")).toBeVisible();
    await page.getByTestId("output-match-path").fill("escalate");
    await page.getByTestId("alert-rule-save").click();
    await expect(page.getByTestId("alert-rules-list")).toContainText("E2E escalations");
  });

  test("is reachable from the sidebar, with its unacknowledged badge", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await expect(page.getByTestId("nav-alerts-badge")).toBeVisible();
  });
});
