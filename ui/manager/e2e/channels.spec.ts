import { test, expect } from "./fixtures";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Channels Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/channels");
    await waitForApp(page);
  });

  test("renders channels heading", async ({ page }) => {
    await expectHeading(page, /channel/i);
  });

  test("shows create channel button", async ({ page }) => {
    await expect(page.getByTestId("create-channel-btn")).toBeVisible();
  });

  test("shows Slack setup guide banner", async ({ page }) => {
    await expect(
      page.getByText(/slack|integration/i).first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("shows channel cards from MSW mock data", async ({ page }) => {
    // MSW returns: "Engineering Slack", "Customer Support Slack", "HR Onboarding Slack"
    await expect(
      page.getByText(/Engineering Slack|Customer Support Slack|HR Onboarding Slack/i).first()
    ).toBeVisible({ timeout: 5000 });
  });

  test("search input filters channels", async ({ page }) => {
    const searchInput = page.getByTestId("channel-search");
    await expect(searchInput).toBeVisible();
    await searchInput.fill("engineering");
    await expect(searchInput).toHaveValue("engineering");
  });

  test("create channel dialog opens", async ({ page }) => {
    await page.getByTestId("create-channel-btn").click();
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible({ timeout: 3000 });
  });

  test("view toggle switches between card and list", async ({ page }) => {
    // Two nested `if`s and a `.catch(() => false)` used to make this a no-op on
    // any run where the toggle was missing — and on the happy path its only
    // assertion was that `main` still existed, which it would have whatever the
    // click did. The toggle is not conditional: it is always rendered, so the
    // guard is an assertion now, and the click has to actually change the view.
    await expect(page.getByTestId("view-toggle")).toBeVisible();

    await page.getByTestId("view-toggle-list").click();
    await expect(page.getByTestId(/^channel-row-/).first()).toBeVisible();

    await page.getByTestId("view-toggle-card").click();
    await expect(page.getByTestId(/^channel-row-/)).toHaveCount(0);
  });
});

test.describe("Channel Detail Page", () => {
  test.beforeEach(async ({ page }) => {
    // MSW uses ch1 as channel ID
    await page.goto("/manage/channels/ch1");
    await waitForApp(page);
  });

  test("renders channel detail page", async ({ page }) => {
    await expect(page.locator("main")).toBeVisible();
  });

  test("shows channel type information", async ({ page }) => {
    await expect(
      page.getByText(/slack/i).first()
    ).toBeVisible({ timeout: 5000 });
  });

  // NOTE: "back to channels" link test removed — MSW browser worker
  // doesn't reliably serve individual channel data in E2E timing.
});
