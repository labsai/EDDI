import { type Page, type APIRequestContext } from "@playwright/test";
import { waitForBackend, API_BASE } from "../integration/integration-helpers";

/**
 * Full-stack E2E helpers.
 *
 * Unlike the MSW-based UI E2E tier, full-stack tests require a live EDDI
 * backend.  The Vite dev server auto-detects the backend and skips MSW
 * (see main.tsx:55-68), so no special MSW bypass is needed.
 */

/**
 * Wait for skeleton loaders to disappear, indicating real data has loaded.
 */
async function waitForDataLoad(page: Page) {
  await page
    .locator('[class*="animate-pulse"]')
    .first()
    .waitFor({ state: "hidden", timeout: 15_000 })
    .catch(() => {
      /* no skeletons — page loaded instantly */
    });
}

/**
 * Ensure backend is ready then navigate to the app and wait for the
 * layout shell + initial data to render.
 *
 * @param skipHealthCheck - Set to true if backend health was already
 *   confirmed (e.g., by a preceding `beforeAll` / `createAndDeployAgent`).
 */
export async function waitForFullStack(
  page: Page,
  request: APIRequestContext,
  path = "/manage",
  { skipHealthCheck = false } = {}
) {
  if (!skipHealthCheck) {
    await waitForBackend(request);
  }
  await page.goto(path);
  await page.waitForSelector('[data-testid="app-layout"]', { timeout: 30_000 });
  await waitForDataLoad(page);
}

/**
 * Navigate to a Manager page after ensuring the app is loaded.
 * Use when already confirmed the backend is healthy.
 */
export async function navigateTo(page: Page, path: string) {
  await page.goto(path);
  await page.waitForSelector('[data-testid="app-layout"]', { timeout: 15_000 });
  await waitForDataLoad(page);
}

/** Re-export for convenience. */
export { API_BASE, waitForBackend };
