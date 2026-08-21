import { type Page, type APIRequestContext, expect } from "@playwright/test";
import { STILL_LOADING } from "../e2e-helpers";
import { waitForBackend, API_BASE } from "../integration/integration-helpers";

/**
 * Full-stack E2E helpers.
 *
 * Unlike the MSW-based UI E2E tier, full-stack tests require a live EDDI
 * backend.  The Vite dev server auto-detects the backend and skips MSW
 * (see `mocksForced` / the probe in main.tsx), so no special MSW bypass is needed.
 */

/**
 * Wait for loading placeholders to disappear, indicating real data has loaded.
 *
 * This carried the same defect the ui tier's `waitForApp` did — waiting on
 * `[class*="animate-pulse"]`, which the never-hiding PlatformStatus dot always
 * matches first, then swallowing the inevitable timeout. It burned the full 15s
 * on every call while waiting for nothing. It now shares the ui tier's
 * placeholder list so the two cannot drift apart, and does not swallow.
 */
async function waitForDataLoad(page: Page) {
  await expect(
    page.locator(STILL_LOADING),
    "the page was still showing loading placeholders — its data never arrived",
  ).toHaveCount(0, { timeout: 15_000 });
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
