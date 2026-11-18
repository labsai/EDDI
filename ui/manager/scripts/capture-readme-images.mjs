#!/usr/bin/env node
// Regenerates the images README.md shows: the banner and the Manager screenshots.
//
// The screenshots come from the Manager running on its mock API (MSW), so they
// need no backend and show the same data every time. They went stale once
// already: the previous set still said "EDDI Manager 6.0.0" at 6.7. Re-run this
// after a visible change to a page it captures.
//
//   cd ui && npm ci                       # once
//   cd manager && npm run dev             # leave running (port 3000)
//   node scripts/capture-readme-images.mjs [--base http://localhost:3000] [--only banner|shots]
//
// Output goes to <repo>/screenshots/. Needs Playwright's Chromium
// (`npx playwright install chromium`), the one the e2e tests use.

import { chromium } from "@playwright/test";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "..", "..", "..");
const outDir = path.join(repoRoot, "screenshots");

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const at = args.indexOf(name);
  return at >= 0 && args[at + 1] ? args[at + 1] : fallback;
};
const base = flag("--base", "http://localhost:3000").replace(/\/$/, "");
const only = flag("--only", "all");

/** ALL_CHAPTERS in src/hooks/use-onboarding.ts: a tour not marked done opens over its page. */
const TOUR_CHAPTERS = ["dashboard", "agents", "workflows", "chat", "resources", "conversations", "groups", "logs", "secrets", "audit", "schedules", "quotas", "coordinator", "orphans"];

/** Each shot: a route, and optionally what to do on the page before the capture. */
const SHOTS = [
  {
    file: "manager-dashboard.png",
    route: "/manage",
  },
  {
    file: "manager-conversation.png",
    route: "/manage/conversationview/conv1",
  },
  {
    file: "manager-group-discussion.png",
    route: "/manage/groups/grp1",
    prepare: async (page) => {
      const question = page.getByPlaceholder(/ask a question/i);
      await question.fill("What are the key benefits of AI agents?");
      await page.getByTestId("start-discussion-btn").click();
      // The mock stream plays a whole peer review; the composer says so until it ends.
      await page.getByPlaceholder(/in progress/i).waitFor({ timeout: 15_000 });
      await page.getByPlaceholder(/in progress/i).waitFor({ state: "detached", timeout: 120_000 });
      // Let the "discussion started / completed" toasts leave.
      await page.waitForTimeout(7000);
    },
  },
];

async function captureBanner(browser) {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 380 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  await page.goto(pathToFileURL(path.join(repoRoot, "branding", "readme-banner", "banner.html")).href);
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: path.join(outDir, "eddi-banner.png") });
  await context.close();
  console.log("wrote screenshots/eddi-banner.png");
}

async function captureShots(browser) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: "dark",
    locale: "en-US",
    timezoneId: "UTC",
  });
  // Mock mode: start-app.tsx starts MSW instead of probing a backend when this is set.
  await context.addInitScript((TOUR_CHAPTERS) => {
    localStorage.setItem("eddi-force-mocks", "true");
    // No first-visit welcome modal or tour offers over the page (use-onboarding.ts).
    localStorage.setItem("eddi-onboarding-welcomed", "true");
    localStorage.setItem("eddi-tour-offers-dismissed", "true");
    for (const chapter of TOUR_CHAPTERS) {
      localStorage.setItem(`eddi-tour-${chapter}`, "done");
    }
  }, TOUR_CHAPTERS);
  const page = await context.newPage();
  for (const shot of SHOTS) {
    // Not "networkidle": the app keeps log and event streams open, so it never idles.
    await page.goto(base + shot.route, { waitUntil: "load" });
    await page.locator("main").first().waitFor();
    await page.waitForTimeout(2500);
    // The demo-mode and update notices are true of the mock, not of the product.
    const dismiss = page.getByRole("button", { name: "Dismiss" });
    for (let left = await dismiss.count(); left > 0; left = await dismiss.count()) {
      await dismiss.first().click();
      await page.waitForTimeout(300);
    }
    if (shot.prepare) {
      await shot.prepare(page);
    }
    // Let entrance animations and count-up numbers settle.
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(outDir, shot.file) });
    console.log(`wrote screenshots/${shot.file}`);
  }
  await context.close();
}

const browser = await chromium.launch();
try {
  if (only === "all" || only === "banner") {
    await captureBanner(browser);
  }
  if (only === "all" || only === "shots") {
    await captureShots(browser);
  }
} finally {
  await browser.close();
}
