import { defineConfig, devices } from "@playwright/test";

const isCI = !!process.env.CI;

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
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },
  projects: [
    // ── Tier 1: UI smoke tests (MSW mocks, fast, no backend needed) ──
    {
      name: "ui",
      testDir: "./e2e",
      testIgnore: ["**/integration/**", "**/fullstack/**"],
      use: { ...devices["Desktop Chrome"] },
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

    // ── Cross-browser (UI tier only, CI/optional) ──
    {
      name: "firefox",
      testDir: "./e2e",
      testIgnore: ["**/integration/**", "**/fullstack/**"],
      use: { ...devices["Desktop Firefox"] },
    },
    {
      name: "webkit",
      testDir: "./e2e",
      testIgnore: ["**/integration/**", "**/fullstack/**"],
      use: { ...devices["Desktop Safari"] },
    },
  ],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:3000",
    reuseExistingServer: !isCI,
  },
});
