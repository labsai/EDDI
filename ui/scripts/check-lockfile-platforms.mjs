#!/usr/bin/env node
/**
 * Fails when ui/package-lock.json has lost the children of an optional
 * wasm32-wasi package.
 *
 * npm on Windows skips `@tailwindcss/oxide-wasm32-wasi` (an optional package for
 * another platform), never resolves its children, and prunes them from the lock
 * on any `npm install` or `npm uninstall`. The lock still looks fine on Windows;
 * the Linux CI runner then fails `npm ci` with "Missing: @emnapi/core from lock
 * file" before it runs anything. This check names the cause instead, and runs in
 * CI (UI Manager Checks) and locally as `npm run lockfile:check` at ui/.
 *
 * It is generic: every lock entry that declares dependencies has each of them
 * resolvable the way Node resolves them (nested first, then up the tree). That
 * covers the oxide case and any other optional package with the same habit, and
 * the explicit list below makes sure the oxide entry itself is still present.
 *
 * Usage: node scripts/check-lockfile-platforms.mjs [path/to/package-lock.json]
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const lockPath = process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), "..", "package-lock.json");
const lock = JSON.parse(readFileSync(lockPath, "utf8"));
const packages = lock.packages ?? {};

/** Optional platform packages whose children Windows prunes. */
const REQUIRED = {
  "node_modules/@tailwindcss/oxide-wasm32-wasi": [
    "@emnapi/core",
    "@emnapi/runtime",
    "@emnapi/wasi-threads",
    "@napi-rs/wasm-runtime",
  ],
};

/** Resolve `name` from the lock entry at `from`, as Node would: walk up node_modules. */
function resolves(from, name) {
  let base = from;
  for (;;) {
    if (packages[`${base ? `${base}/` : ""}node_modules/${name}`]) return true;
    if (!base) return false;
    // One level up: `a/node_modules/b` -> `a`; a top-level `node_modules/b` or a
    // workspace directory (manager, chat, packages/x) -> the root.
    const idx = base.lastIndexOf("/node_modules/");
    base = idx >= 0 ? base.slice(0, idx) : "";
  }
}

const problems = new Set();

for (const [entry, children] of Object.entries(REQUIRED)) {
  if (!packages[entry]) {
    problems.add(`${entry} is missing from the lock`);
    continue;
  }
  for (const child of children) {
    if (!resolves(entry, child)) problems.add(`${entry} cannot resolve its dependency ${child}`);
  }
}

for (const [entry, meta] of Object.entries(packages)) {
  if (entry === "" || meta.link) continue;
  for (const name of Object.keys(meta.dependencies ?? {})) {
    if (!resolves(entry, name)) problems.add(`${entry} cannot resolve its dependency ${name}`);
  }
}

if (problems.size > 0) {
  console.error(`${lockPath} is missing packages another platform needs:\n`);
  for (const p of problems) console.error(`  - ${p}`);
  console.error(
    "\nThis is what npm on Windows does to optional wasm32-wasi packages (see ui/manager/AGENTS.md)." +
      "\nRestore ui/package-lock.json from the last version CI accepted, or regenerate it on Linux.",
  );
  process.exit(1);
}

console.log(`${lockPath}: every locked dependency resolves, including the wasm32-wasi children.`);
