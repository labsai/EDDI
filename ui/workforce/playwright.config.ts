import { defineConfig, devices } from "@playwright/test";
import { readFileSync } from "node:fs";

const isCI = !!process.env.CI;

/**
 * Workforce's E2E suite: the `ui` tier only — the browser against Workforce's own
 * dev server with the shared MSW mock API, no backend. The backend-facing tiers
 * (integration, full stack, auth) stay in ui/manager, where the compose files and
 * the realm live; they reach Workforce through the jar EDDI serves.
 *
 * The port honours `PORT` (default 3002, Workforce's dev port), so two worktrees
 * can run side by side — the Manager's playwright.config.ts has the history of
 * what a pinned port did.
 */
const PORT = Number(process.env.PORT) || 3002;
const BASE_URL = `http://localhost:${PORT}`;

/** The origin the committed seed is written against. */
const SEED_ORIGIN = "http://localhost:3002";

/**
 * The seeded localStorage, re-pointed at this run's port, plus the flag that
 * pins the app to the mock API (`eddi-force-mocks`): without it, a machine with
 * EDDI up would drive the real API and fail every assertion written against a
 * fixture value. The flag is merged in, not mapped over the seed, so an empty
 * seed still forces mocks.
 */
const storageState = (() => {
  const seed = JSON.parse(readFileSync(new URL("./e2e/storage-state.json", import.meta.url), "utf8")) as {
    cookies?: [];
    origins?: { origin: string; localStorage: { name: string; value: string }[] }[];
  };
  const FLAG = { name: "eddi-force-mocks", value: "true" };
  const origins = (seed.origins ?? []).map((o) => (o.origin === SEED_ORIGIN ? { ...o, origin: BASE_URL } : o));
  const app = origins.find((o) => o.origin === BASE_URL);
  return {
    cookies: seed.cookies ?? [],
    origins: app
      ? origins.map((o) => (o === app ? { ...o, localStorage: [...o.localStorage, FLAG] } : o))
      : [...origins, { origin: BASE_URL, localStorage: [FLAG] }],
  };
})();

export default defineConfig({
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  reporter: [["html"], ...(isCI ? ([["json", { outputFile: "test-results.json" }]] as const) : [])],
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    storageState,
  },
  projects: [
    {
      name: "ui",
      testDir: "./e2e",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    command: "npm run dev",
    url: BASE_URL,
    reuseExistingServer: !isCI,
  },
});
