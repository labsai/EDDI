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
  let raw;
  try {
    raw = execFileSync(NPM, ["audit", "--json", ...extraArgs], {
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
      shell: IS_WINDOWS,
    });
  } catch (err) {
    // npm audit exits non-zero merely because it found something, and still
    // writes a full report to stdout. Anything else is a real failure.
    if (!err.stdout) throw err;
    raw = err.stdout;
  }

  let report;
  try {
    report = JSON.parse(raw);
  } catch {
    throw new Error(`npm audit did not return JSON: ${String(raw).slice(0, 300)}`);
  }

  // A gate that cannot read its input must FAIL CLOSED. On a registry outage npm
  // emits {"error": {...}} with no `vulnerabilities` key; treating that as "no
  // findings" would print success and let anything through — worse than having no
  // gate at all, because it looks like it is protecting you.
  if (report.error) {
    throw new Error(`npm audit reported an error: ${JSON.stringify(report.error).slice(0, 300)}`);
  }
  if (
    typeof report.vulnerabilities !== "object" ||
    report.vulnerabilities === null ||
    typeof report.metadata !== "object"
  ) {
    throw new Error(
      "npm audit JSON is missing the expected `vulnerabilities`/`metadata` fields — " +
        "refusing to report success on an audit that could not be read.",
    );
  }
  return report;
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
/**
 * An entry accepts an advisory only for the package it was reasoned about.
 * Matching on the GHSA id alone would silently suppress the same advisory if it
 * later surfaced under a different production dependency, where the "unreachable
 * code path" argument may not hold at all.
 */
const isAccepted = (a) => ALLOWLIST[a.id]?.package === a.package;

const unaccepted = prod.filter((a) => !isAccepted(a));
const accepted = prod.filter(isAccepted);

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
