import { type Page, expect } from "@playwright/test";

/**
 * Shared E2E helpers.
 *
 * The Vite dev server auto-starts MSW browser worker when the real
 * backend is unreachable, so all E2E tests run against mock data with
 * zero extra setup.
 */

/**
 * Wait for the app to initialise: shell mounted, route chunk in, data rendered.
 *
 * ## The wait this replaces never waited for anything
 *
 * It was `locator('[class*="animate-pulse"]').first().waitFor({state:"hidden"})`
 * followed by `.catch(() => {})`, commented "no skeletons found — page loaded
 * instantly". Both halves were wrong.
 *
 * `animate-pulse` is worn by 43 elements, and most are decoration rather than
 * loading state — including the PlatformStatus dot, which lives in `TopBar`
 * (rendered *before* `<main>`, so it is always `.first()`) and pulses in all
 * three of its states. It never hides. So the wait could only ever time out,
 * and the `.catch` swallowed that: every UI test paid the full 10s and then
 * proceeded without having waited for data at all. With ~198 tests at one
 * worker, that is most of CI's 34-minute UI E2E run spent on a no-op.
 *
 * `page-loader.tsx` documents this exact hazard for the routing tests —
 * "`animate-pulse` is also worn by live-status dots elsewhere in the shell, so
 * 'is the page still loading?' cannot be answered by looking for that class".
 * This helper was the place that still answered it that way.
 *
 * ## What it waits for now
 *
 * Real loading placeholders inside the content area, matched by **semantics,
 * never by animation class**. That distinction is the whole lesson above:
 * `animate-pulse` and `animate-spin` are presentation, and this app uses both
 * for decoration as well as for loading. `coordinator.tsx:188` is the proof — a
 * `RefreshCw` that spins permanently as an auto-refresh indicator, its duration
 * set to the refresh interval. A `.animate-spin` selector hangs on it forever,
 * exactly as the old one hung on the PlatformStatus dot. I tried it; it broke
 * three Coordinator tests.
 *
 * So this matches two things only:
 *
 *  1. the `Skeleton` primitive, marked `data-slot="skeleton"`
 *  2. any testid *containing* "loading" — `agents-loading`, `loading-skeleton`,
 *     `memory-loading-spinner`. A `$=` suffix match, which is what this had
 *     first, silently misses the last two.
 *
 * `toHaveCount(0)` rather than `.first()` so a second placeholder outliving the
 * first cannot let the wait through, and no `.catch` — data that never arrives
 * is the failure this exists to surface.
 *
 * **Known gap, stated rather than papered over:** a page whose loading state is
 * a bare `Loader2` with no testid is not covered, and there are ~18 of them
 * (`variables.tsx:258`, `logs.tsx:616`, `secrets.tsx`, …). For those this waits
 * for nothing and returns immediately. Closing it properly means giving those
 * loaders a testid or a shared marked component — not widening this selector,
 * which is precisely how it caught the coordinator's decoration. Until then the
 * per-test assertions and the unhandled-request fixture are what catch a page
 * that failed to load.
 */
export const STILL_LOADING = [
  'main [data-slot="skeleton"]',
  'main [data-testid*="loading"]',
].join(", ");

/**
 * The two shells this app has.
 *
 * `/manage/*` renders AppLayout; `/workforce/*` renders WorkforceLayout, which
 * is a different component tree with no `app-layout` anywhere in it. Waiting on
 * `app-layout` alone made this helper unusable for half the routes, which is a
 * fair part of why the Workforce surface had no E2E spec at all.
 */
const APP_SHELL = '[data-testid="app-layout"], [data-testid="workforce-layout"]';

export async function waitForApp(page: Page) {
  // Whichever shell this route uses.
  await page.waitForSelector(APP_SHELL, { timeout: 15_000 });

  // The route's code-split chunk (SuspendedOutlet's fallback).
  await expect(page.getByTestId("page-loader")).toHaveCount(0, { timeout: 15_000 });

  // The page's own data.
  await expect(
    page.locator(STILL_LOADING),
    "the page was still showing loading placeholders — its data never arrived",
  ).toHaveCount(0, { timeout: 15_000 });
}

/** Assert the visible h1 heading on the current page. */
export async function expectHeading(page: Page, text: string | RegExp) {
  await expect(page.locator("h1").first()).toContainText(text);
}
