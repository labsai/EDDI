/**
 * The one name shared by the MSW recorder and the Playwright fixture that reads
 * it. Its own module on purpose, for two reasons.
 *
 * **It must not be duplicated.** A hand-synced copy is the single way this guard
 * can fail silently: change it on one side and the fixture reads an empty array
 * forever, so every test goes green and nothing says why — the exact failure
 * mode the surrounding work exists to remove.
 *
 * **It must not drag MSW into Node.** `browser.ts` calls `setupWorker()` at
 * module scope, so importing the constant from there would evaluate
 * `msw/browser` inside the Playwright process. This file has no imports and no
 * side effects, so both sides can share it safely.
 *
 * Written to `sessionStorage` (survives same-origin navigation, per-tab) with an
 * in-document array as a fallback when storage is unavailable.
 */
export const UNHANDLED_API_REQUESTS_KEY = "__EDDI_UNHANDLED_API__";
