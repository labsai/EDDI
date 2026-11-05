#!/usr/bin/env node
/**
 * Install Playwright's Chromium for CI without waiting on the Ubuntu mirror.
 *
 * `npx playwright install --with-deps chromium` always runs apt-get, and on
 * ubuntu-latest that means about 26 MB of fonts (CJK, unifont, xfonts) from
 * azure.archive.ubuntu.com before a single test starts. None of them are needed
 * here: the suites take screenshots only on failure and compare none. When the
 * mirror crawls — 25–50 KB/s on 2026-10-26 — the step runs into its 10-minute
 * limit and the E2E jobs fail with every test unrun, twice in a row on main.
 *
 * The runner image already carries Chromium's shared libraries (it ships
 * Google Chrome). So this downloads the browser from Playwright's own CDN,
 * tries to launch it, and goes to apt only when it will not start — a newer
 * image or another runner still works, it just pays the apt cost again.
 */
import { spawnSync } from 'node:child_process';
import { chromium } from '@playwright/test';

function playwright(...args) {
  const result = spawnSync('npx', ['playwright', ...args], {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.status !== 0) {
    console.error(`::error::npx playwright ${args.join(' ')} failed`);
    process.exit(result.status ?? 1);
  }
}

async function launches() {
  try {
    const browser = await chromium.launch();
    await browser.close();
    return true;
  } catch (error) {
    // Playwright lists the missing libraries; the head of the message is enough.
    console.log(String(error.message).split('\n').slice(0, 8).join('\n'));
    return false;
  }
}

playwright('install', 'chromium');

if (await launches()) {
  console.log("Chromium launches with the runner's libraries; skipping apt.");
} else {
  console.log(
    "::warning::Chromium does not launch with the runner's libraries; installing its system dependencies with apt.",
  );
  playwright('install-deps', 'chromium');
  if (!(await launches())) {
    console.error('::error::Chromium still does not launch after install-deps.');
    process.exit(1);
  }
}
