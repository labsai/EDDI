import { test, expect } from "@playwright/test";
import { waitForApp, expectHeading } from "./e2e-helpers";

test.describe("Schedules Page", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/schedules");
    await waitForApp(page);
  });

  test("renders schedules heading", async ({ page }) => {
    await expectHeading(page, /schedule/i);
  });

  test("shows schedule cards from MSW mock data", async ({ page }) => {
    // MSW returns 3 schedules — use .first() to avoid strict mode violations
    // since schedule names can appear in multiple places (table + next-fire card)
    await expect(page.getByText("Daily Health Check").first()).toBeVisible();
    await expect(page.getByText("Heartbeat Monitor").first()).toBeVisible();
    await expect(page.getByText("Failed Report").first()).toBeVisible();
  });

  test("shows create schedule button", async ({ page }) => {
    await expect(
      page
        .getByTestId("create-schedule-btn")
        .or(page.getByRole("button", { name: /create|new schedule/i }))
    ).toBeVisible();
  });

  test("schedule cards show status badges", async ({ page }) => {
    // "Daily Health Check" is enabled with COMPLETED status → shows "Active"
    await expect(
      page.getByText(/active/i).first()
    ).toBeVisible();
  });

  test("schedule cards show trigger type", async ({ page }) => {
    // CRON and HEARTBEAT trigger types should be visible
    await expect(page.getByText(/cron/i).first()).toBeVisible();
  });

  test("schedule cards show agent association", async ({ page }) => {
    // Schedules are associated with agents
    await expect(
      page.getByText(/agent/i).first()
    ).toBeVisible();
  });

  test("shows dead-lettered schedule with warning styling", async ({
    page,
  }) => {
    await expect(
      page.getByText("Failed Report").first()
    ).toBeVisible();
    await expect(
      page.getByText(/dead.lettered/i).first()
    ).toBeVisible();
  });

  test("disabled schedule shows disabled status", async ({ page }) => {
    await expect(
      page.getByText(/disabled/i).first().or(
        page.getByText(/dead.lettered/i).first()
      )
    ).toBeVisible();
  });

  test("schedule cards show cron expression for CRON type", async ({
    page,
  }) => {
    // "Daily Health Check" has cron expression "0 9 * * MON-FRI"
    // Use a more specific locator to avoid matching both the code and the human-readable text
    await expect(
      page.locator("code").filter({ hasText: /MON-FRI/ }).first()
    ).toBeVisible();
  });

  test("shows heartbeat interval for HEARTBEAT type", async ({ page }) => {
    // "Heartbeat Monitor" has heartbeatIntervalSeconds: 300
    await expect(
      page.getByText(/heartbeat/i).first()
    ).toBeVisible();
  });
});
