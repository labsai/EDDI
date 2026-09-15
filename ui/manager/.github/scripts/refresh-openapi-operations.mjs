/**
 * Refresh the snapshot of EDDI's API surface that `openapi-contract.test.ts`
 * checks the MSW handlers against.
 *
 * ## Why a snapshot rather than a live fetch
 *
 * The contract check has to run on every PR, and booting EDDI plus a database
 * per PR is minutes of runner time — the same trade `e2e.yml` already makes for
 * the backend tiers. A snapshot makes the check free and, more usefully, makes
 * backend drift show up as a **reviewable diff** instead of a red build nobody
 * can read: refresh it and the added/removed operations are right there in the
 * PR.
 *
 * The cost is that the snapshot can go stale. That is a deliberate trade rather
 * than an oversight: a stale snapshot still catches the failure this exists to
 * catch — a mock inventing an endpoint the backend never had — and the
 * `integration` tier catches the other direction against a live backend.
 *
 * Only method + path is stored. Response-schema validation would need the whole
 * 508 KB document; if that is ever wanted, widen this script and the test
 * together rather than checking in the full spec for a check nothing performs.
 *
 * ## Usage
 *
 *   docker compose -f docker-compose.integration.yml up -d --wait
 *   node .github/scripts/refresh-openapi-operations.mjs
 *
 * Or against any reachable instance:
 *
 *   EDDI_URL=http://localhost:7070 node .github/scripts/refresh-openapi-operations.mjs
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const OUT = path.join(ROOT, "src/test/mocks/openapi-operations.json");
const EDDI_URL = process.env.EDDI_URL ?? "http://localhost:7070";
const METHODS = ["get", "post", "put", "patch", "delete"];

const url = `${EDDI_URL}/openapi?format=json`;
process.stdout.write(`Fetching ${url}\n`);

let spec;
try {
  const res = await fetch(url, { signal: AbortSignal.timeout(30_000) });
  if (!res.ok) {
    console.error(`ERROR: ${url} responded ${res.status}`);
    process.exit(1);
  }
  spec = await res.json();
} catch (err) {
  console.error(`ERROR: could not reach ${url} — ${err instanceof Error ? err.message : err}`);
  console.error("Start a backend first: docker compose -f docker-compose.integration.yml up -d --wait");
  process.exit(1);
}

const operations = [];
for (const [p, ops] of Object.entries(spec.paths ?? {})) {
  for (const method of Object.keys(ops)) {
    if (METHODS.includes(method)) operations.push(`${method.toUpperCase()} ${p}`);
  }
}

if (operations.length === 0) {
  console.error("ERROR: the document carried no operations — refusing to write an empty snapshot.");
  process.exit(1);
}

operations.sort();

// Read only to compute the added/removed diff, so a corrupt existing snapshot
// must not throw a raw SyntaxError out of the one command that would replace
// it. The fetch path above is careful; this was not.
let previous = [];
try {
  if (fs.existsSync(OUT)) {
    const candidate = JSON.parse(fs.readFileSync(OUT, "utf8")).operations;
    // A valid JSON file whose `operations` is not an array of strings would
    // otherwise sail past this and throw a TypeError further down, outside
    // the recovery this block exists to provide.
    if (candidate !== undefined) {
      if (!Array.isArray(candidate) || !candidate.every((op) => typeof op === "string")) {
        throw new TypeError("snapshot `operations` is not an array of strings");
      }
      previous = candidate;
    }
  }
} catch {
  console.warn("Existing snapshot is unreadable — writing a fresh one, skipping the diff.");
}

fs.writeFileSync(
  OUT,
  JSON.stringify(
    {
      "//": "Generated. Do not edit by hand — run: node .github/scripts/refresh-openapi-operations.mjs",
      eddiVersion: spec.info?.version ?? "unknown",
      operations,
    },
    null,
    2,
  ) + "\n",
);

const added = operations.filter((o) => !previous.includes(o));
const removed = previous.filter((o) => !operations.includes(o));

process.stdout.write(
  `Wrote ${operations.length} operations for EDDI ${spec.info?.version ?? "unknown"} → ${path.relative(ROOT, OUT)}\n`,
);
if (added.length) {
  process.stdout.write(`\n  ${added.length} added:\n`);
  added.forEach((o) => process.stdout.write(`    + ${o}\n`));
}
if (removed.length) {
  process.stdout.write(`\n  ${removed.length} removed:\n`);
  removed.forEach((o) => process.stdout.write(`    - ${o}\n`));
}
if (!added.length && !removed.length) process.stdout.write("  No change.\n");
