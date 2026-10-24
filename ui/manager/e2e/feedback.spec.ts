import { test, expect } from "./fixtures";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Feedback Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/feedback");
    await waitForApp(page);
  });

  test("opens on Needs attention with the mock ratings", async ({ page }) => {
    await expectHeading(page, /feedback/i);
    await expect(page.getByTestId("feedback-tab-attention")).toHaveAttribute("aria-selected", "true");
    await expect(page.getByTestId("rating-row-r1")).toBeVisible();
    await expect(page.getByTestId("rating-row-r2")).toHaveCount(0);
  });

  test("marking a rating reviewed takes it off the list", async ({ page }) => {
    await page.getByTestId("rating-review-r1").click();
    await expect(page.getByTestId("rating-row-r1")).toHaveCount(0);
  });

  test("All ratings shows the overview and keeps filters in the URL", async ({ page }) => {
    await page.getByTestId("feedback-tab-all").click();
    await expect(page.getByTestId("feedback-overview")).toBeVisible();
    await page.getByTestId("feedback-filter-kind").selectOption("message");
    await expect(page).toHaveURL(/kind=message/);
    await expect(page.getByTestId("rating-row-r4")).toBeVisible();
  });

  test("is reachable from the sidebar, with its attention badge", async ({ page }) => {
    await page.goto("/manage");
    await waitForApp(page);
    await expect(page.getByTestId("nav-feedback-badge")).toBeVisible();
  });
});
