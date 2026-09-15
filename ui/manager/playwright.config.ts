import { defineConfig, devices } from "@playwright/test";
import { readFileSync } from "node:fs";

const isCI = !!process.env.CI;

/**
 * Dev-server port, honouring `PORT` exactly as `vite.config.ts` already does
 * ("so two worktrees can run dev servers side by side").
 *
 * Without this, `baseURL` was pinned to 3000 while `reuseExistingServer` is on
 * locally — so a run started from one worktree silently drove whatever dev
 * server another worktree had left on 3000, and every test failed on a missing
 * `app-layout` for reasons that had nothing to do with the branch under test.
 * `PORT=3100 npm run test:e2e` now isolates a run completely.
 */
const PORT = Number(process.env.PORT) || 3000;
const BASE_URL = `http://localhost:${PORT}`;

/**
 * Seeded localStorage (onboarding already dismissed), re-pointed at whatever
 * port this run uses.
 *
 * `storageState` is keyed by origin, and `e2e/storage-state.json` has
 * `http://localhost:3000` baked in — so on any other port the seed silently did
 * not apply, the onboarding overlay came up, and every test failed waiting for
 * `app-layout`. Rewriting the origin here keeps the values declarative in the
 * JSON file while letting `PORT` actually work.
 */
/** The origin the committed seed is written against. */
const SEED_ORIGIN = "http://localhost:3000";

const storageState = (() => {
  const seed = JSON.parse(
    readFileSync(new URL("./e2e/storage-state.json", import.meta.url), "utf8"),
  ) as {
    // The seed carries no cookies today; typing it as an empty tuple keeps it
    // assignable to Playwright's cookie shape without restating that shape.
    cookies?: [];
    origins?: { origin: string; localStorage: { name: string; value: string }[] }[];
  };
  return {
    cookies: seed.cookies ?? [],
    // Only the entry written against the default port is re-pointed. Mapping
    // *every* origin onto BASE_URL would silently merge a second one — an auth
    // provider, a docs host — into the app's, and the symptom would read as
    // "the seed didn't apply" rather than as this line.
    origins: (seed.origins ?? []).map((o) =>
      o.origin === SEED_ORIGIN ? { ...o, origin: BASE_URL } : o,
    ),
  };
})();

/**
 * The same seed plus the flag that pins the app to MSW (see the `ui` project).
 *
 * Builds the origin entry rather than mapping over whatever is there: a `map`
 * over an empty `origins` list produces an empty list, so emptying
 * `storage-state.json` would silently stop forcing mocks and the "no backend"
 * tier would go back to driving a real backend without a word. This is a guard
 * that has to hold, so it does not depend on the seed's contents.
 */
function withForcedMocks(state: typeof storageState) {
  const FLAG = { name: "eddi-force-mocks", value: "true" };
  const hasAppOrigin = state.origins.some((o) => o.origin === BASE_URL);

  // Merge into the app's origin, leave any other origin untouched, and create
  // the entry if the seed has none — a `map` alone would produce nothing from
  // an empty list and the "no backend" tier would quietly go back to driving a
  // real backend.
  const origins = state.origins.map((o) =>
    o.origin === BASE_URL ? { ...o, localStorage: [...o.localStorage, FLAG] } : o,
  );

  return {
    ...state,
    origins: hasAppOrigin
      ? origins
      : [...origins, { origin: BASE_URL, localStorage: [FLAG] }],
  };
}

/**
 * Three-tier Playwright configuration:
 *
 * ┌─────────────┬──────────────────────────────┬───────────────┐
 * │ Tier        │ What it does                 │ Backend?      │
 * ├─────────────┼──────────────────────────────┼───────────────┤
 * │ ui          │ Browser + MSW mocks          │ No            │
 * │ integration │ API-only, real backend       │ Yes           │
 * │ fullstack   │ Browser + real backend       │ Yes           │
 * └─────────────┴──────────────────────────────┴───────────────┘
 *
 * Usage:
 *   npm run test:e2e              → ui tier only (fast, no backend)
 *   npm run test:e2e:integration  → API integration tests (needs backend)
 *   npm run test:e2e:fullstack    → browser + real backend (needs backend)
 *   npm run test:e2e:all          → all tiers
 */
export default defineConfig({
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  reporter: [
    ["html"],
    ...(isCI
      ? ([["json", { outputFile: "test-results.json" }]] as const)
      : []),
  ],
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    storageState,
  },
  projects: [
    // ── Tier 1: UI smoke tests (MSW mocks, fast, no backend needed) ──
    {
      name: "ui",
      testDir: "./e2e",
      testIgnore: ["**/integration/**", "**/fullstack/**"],
      use: {
        ...devices["Desktop Chrome"],
        // This tier is "MSW mocks, no backend" — so say so, rather than letting
        // `main.tsx`'s runtime probe decide. Without the flag, running this tier
        // on a machine where EDDI happens to be up drove the real API and failed
        // every assertion written against a fixture value. Only this project
        // sets it; `integration` and `fullstack` want the real backend.
        storageState: withForcedMocks(storageState),
      },
    },

    // ── Tier 2: API integration (real backend, no browser rendering) ──
    {
      name: "integration",
      testDir: "./e2e/integration",
      use: { ...devices["Desktop Chrome"] },
    },

    // ── Tier 3: Full-stack (browser + real backend) ──
    {
      name: "fullstack",
      testDir: "./e2e/fullstack",
      use: { ...devices["Desktop Chrome"] },
    },

    // No `firefox` / `webkit` projects. Two existed and neither could ever run:
    // no npm script referenced them, and `e2e.yml` installs `--with-deps
    // chromium` only, so `npx playwright test --project=firefox` fails on a
    // missing browser. Configuration that reads as cross-browser coverage while
    // providing none is worse than none at all. Reinstate them together with the
    // browser install and a script that invokes them.
  ],
  webServer: {
    command: "npm run dev",
    url: BASE_URL,
    reuseExistingServer: !isCI,
  },
});
