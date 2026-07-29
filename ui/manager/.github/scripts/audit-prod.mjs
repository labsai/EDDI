#!/usr/bin/env node
/**
 * Fail CI on any *new* advisory affecting a production dependency.
 *
 * Why not plain `npm audit`:
 *  - It counts dev-only toolchain transitives (eslint, vitest coverage -> glob ->
 *    minimatch -> brace-expansion). Those are never shipped in the built asset,
 *    so failing on them trains everyone to ignore the step.
 *  - It has no allowlist, so a single advisory that does not apply to this app
 *    would wedge the pipeline until someone downgrades — which is how you end up
 *    trading a non-exploitable finding for an exploitable one.
 *
 * So: audit production dependencies only, fail on anything not explicitly
 * accepted below, and report dev findings without failing.
 *
 * Adding to ALLOWLIST is a security decision. Record *why the code path is
 * unreachable in this app*, not merely that it is inconvenient, and mirror the
 * reasoning in SECURITY.md.
 */

import { execFileSync } from "node:child_process";

const ALLOWLIST = {
  "GHSA-qwww-vcr4-c8h2": {
    package: "react-router",
    reason:
      "RSC-mode CSRF. This app is a pure client SPA (BrowserRouter, declarative " +
      "<Routes> only, no createBrowserRouter/loaders/actions, no server runtime), " +
      "so the vulnerable path is unreachable. npm's suggested fix downgrades to " +
      "7.11.0, which re-introduces GHSA-wrjc-x8rr-h8h6 (open redirect -> XSS) " +
      "that DOES apply here. Re-evaluate if this app adopts the data router or SSR.",
  },
};

// Windows resolves the npm CLI as npm.cmd, and Node 20+ refuses to spawn a
// .cmd without a shell (hardening for CVE-2024-27980). The arguments below are
// fixed literals, never user input, so enabling the shell here is safe.
const IS_WINDOWS = process.platform === "win32";
const NPM = IS_WINDOWS ? "npm.cmd" : "npm";

/** `npm audit` exits non-zero when it finds anything, so capture rather than throw. */
function audit(extraArgs) {
  try {
    return JSON.parse(
      execFileSync(NPM, ["audit", "--json", ...extraArgs], {
        encoding: "utf8",
        maxBuffer: 32 * 1024 * 1024,
        shell: IS_WINDOWS,
      }),
    );
  } catch (err) {
    if (err.stdout) return JSON.parse(err.stdout);
    throw err;
  }
}

/** Collect { id, package, severity, title } for each distinct advisory. */
function advisories(report) {
  const out = new Map();
  for (const [pkg, entry] of Object.entries(report.vulnerabilities ?? {})) {
    for (const via of entry.via ?? []) {
      if (typeof via !== "object" || !via.url) continue;
      const id = via.url.split("/").pop();
      if (!out.has(id)) {
        out.set(id, { id, package: pkg, severity: via.severity ?? entry.severity, title: via.title ?? "" });
      }
    }
  }
  return [...out.values()];
}

const prod = advisories(audit(["--omit=dev"]));
const unaccepted = prod.filter((a) => !ALLOWLIST[a.id]);
const accepted = prod.filter((a) => ALLOWLIST[a.id]);

for (const a of accepted) {
  console.log(`accepted  ${a.id}  ${a.package} (${a.severity})`);
  console.log(`          ${ALLOWLIST[a.id].reason.split(". ")[0]}.`);
}

const devOnly = advisories(audit([])).filter(
  (a) => !prod.some((p) => p.id === a.id),
);
if (devOnly.length) {
  console.log(`\n${devOnly.length} dev-only advisory/advisories (not shipped, not failing):`);
  for (const a of devOnly) console.log(`  ${a.severity.padEnd(9)} ${a.package}  ${a.id}`);
}

if (unaccepted.length) {
  console.error(`\n::error::${unaccepted.length} unaccepted advisory/advisories in production dependencies:`);
  for (const a of unaccepted) {
    console.error(`  ${a.severity.padEnd(9)} ${a.package}  ${a.id}  ${a.title}`);
  }
  console.error(
    "\nFix by upgrading. Only add to ALLOWLIST in .github/scripts/audit-prod.mjs " +
      "if the vulnerable code path is genuinely unreachable here — and say why.",
  );
  process.exit(1);
}

console.log("\nNo unaccepted advisories in production dependencies.");
