import { type Page, expect } from "@playwright/test";

/**
 * Shared E2E helpers.
 *
 * The Vite dev server auto-starts MSW browser worker when the real
 * backend is unreachable, so all E2E tests run against mock data with
 * zero extra setup.
 */

/**
 * Wait for the app to initialise: MSW probe + first data render.
 * We wait for the skeleton loaders to disappear (data has loaded).
 */
export async function waitForApp(page: Page) {
  // Wait for the layout shell to be present
  await page.waitForSelector('[data-testid="app-layout"]', { timeout: 15000 });

  // Give MSW worker time to start + first API calls to resolve
  // We wait until there are no skeleton shimmer elements left
  await page
    .locator('[class*="animate-pulse"]')
    .first()
    .waitFor({ state: "hidden", timeout: 10000 })
    .catch(() => {
      /* no skeletons found — page loaded instantly */
    });
}

/** Assert the visible h1 heading on the current page. */
export async function expectHeading(page: Page, text: string | RegExp) {
  await expect(page.locator("h1").first()).toContainText(text);
}
