import { type Page, type APIRequestContext, expect } from "@playwright/test";
import { STILL_LOADING } from "../e2e-helpers";
import { waitForBackend, API_BASE } from "../integration/integration-helpers";

/**
 * Full-stack E2E helpers.
 *
 * Unlike the MSW-based UI E2E tier, full-stack tests require a live EDDI
 * backend. `main.tsx` decides which it gets by probing the backend at startup,
 * and — this is the part that matters here — falls back to MSW on any failure.
 *
 * That fallback is silent by construction, and the tier's assertions are
 * shape-based, so fixtures satisfy them. Nothing used to check which of the two
 * a run had actually used: a probe that lost a race with Vite's first-load
 * transform produced a full green full-stack report that proved nothing about
 * EDDI at all. `assertRealBackend` is the check, and every navigation helper
 * below runs it.
 */

/**
 * Fail the test if the app is running on mocks.
 *
 * `main.tsx` probes `/agentstore/agents/descriptors?limit=1` with a 1500 ms
 * timeout and starts MSW on ANY failure — a slow dev server counts. It sets
 * `__EDDI_MOCK_ACTIVE__` when it does, which is the only reliable signal: the
 * mock-data banner can be suppressed with `?hideMockBanner=true`, and every
 * loading affordance this tier waits on is satisfied by MSW *sooner* than by a
 * real backend.
 */
async function assertRealBackend(page: Page) {
  const onMocks = await page.evaluate(
    () => (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ === true,
  );
  expect(
    onMocks,
    "this full-stack test ran against MSW, not EDDI — main.tsx's startup probe " +
      "of /agentstore/agents/descriptors?limit=1 (1500 ms) failed, so the app fell " +
      "back to mocks and everything below proves nothing about the real backend",
  ).toBe(false);
}

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
  await assertRealBackend(page);
  await waitForDataLoad(page);
}

/**
 * Navigate to a Manager page after ensuring the app is loaded.
 * Use when already confirmed the backend is healthy.
 */
export async function navigateTo(page: Page, path: string) {
  await page.goto(path);
  await page.waitForSelector('[data-testid="app-layout"]', { timeout: 15_000 });
  await assertRealBackend(page);
  await waitForDataLoad(page);
}

/** Re-export for convenience. */
export { API_BASE, waitForBackend };
