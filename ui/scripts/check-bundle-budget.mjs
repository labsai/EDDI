#!/usr/bin/env node
/**
 * First-load bundle budget for the UIs (frontend unification plan, phase 0).
 *
 *   node ui/scripts/check-bundle-budget.mjs <app> [--json]
 *
 * <app> is a key of `ui/bundle-budget.json` (`manager`, `chat`). Run it after
 * `npm run build`, from anywhere: paths resolve against this file.
 *
 * ## What it measures
 *
 * For every HTML shell the app lists, the scripts and stylesheets the browser
 * fetches BEFORE any lazy chunk: the `<script src>` entries, every
 * `<link rel="modulepreload">` (Vite emits one per chunk the entry imports
 * statically, so the browser needs it before the first render) and every
 * `<link rel="stylesheet">`. Each file is gzipped at level 9 and the sizes are
 * summed. A dynamic `import()` is not in the shell, so it is not counted —
 * which is the point: code that moves behind one leaves the number.
 *
 * Files the backend renders at request time (`/manage/__auth_config__.js`,
 * `/chat/__config__.js` and everything under `/branding/`, the deployment's
 * brand) are not in `dist/` and are skipped by name. Any OTHER reference that is not in `dist/` fails the check:
 * silently skipping it would make the number smaller for the wrong reason.
 *
 * Sizes are reported in KB of 1000 bytes, the unit the plan's §1.2 used.
 *
 * ## Raising a budget
 *
 * The budgets are the phase-0 numbers plus a small headroom. Raising one is a
 * decision, not a fix (plan §8): say why in the commit and the changelog.
 */

import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const HERE = dirname(fileURLToPath(import.meta.url));
const UI_ROOT = resolve(HERE, "..");
const BUDGET_FILE = join(UI_ROOT, "bundle-budget.json");

/** Rendered by EDDI per request; never part of a build. */
const SERVER_RENDERED = /^\/branding\/|\/__[a-z_]+__\.js$/;

/**
 * Every asset a shell loads up front, in document order, without duplicates.
 * Attribute order varies between Vite versions, so each tag is parsed whole.
 */
export function firstLoadAssets(html) {
  const refs = [];
  for (const [tag] of html.matchAll(/<(script|link)\b[^>]*>/gi)) {
    const attr = (name) => {
      const m = new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, "i").exec(tag);
      return m ? (m[2] ?? m[3] ?? m[4]) : undefined;
    };
    if (/^<script/i.test(tag)) {
      const src = attr("src");
      if (src) refs.push(src);
    } else {
      const rel = (attr("rel") ?? "").toLowerCase().split(/\s+/);
      const href = attr("href");
      if (href && (rel.includes("stylesheet") || rel.includes("modulepreload"))) refs.push(href);
    }
  }
  return [...new Set(refs)];
}

function gzipSize(path) {
  return gzipSync(readFileSync(path), { level: 9 }).length;
}

const kb = (bytes) => Math.round(bytes / 100) / 10;

/**
 * Measure one shell. `dist` is the build output; `stripPrefix` removes the URL
 * prefix EDDI serves the app under, where the build writes files at the root.
 */
export function measurePage(dist, page, stripPrefix = "") {
  const shell = join(dist, page);
  if (!existsSync(shell)) throw new Error(`${shell} does not exist — run \`npm run build\` first`);
  const files = [];
  for (const ref of firstLoadAssets(readFileSync(shell, "utf8"))) {
    if (/^(https?:)?\/\//i.test(ref)) throw new Error(`${page} loads ${ref} from another origin`);
    if (SERVER_RENDERED.test(ref)) continue;
    let path = ref.split(/[?#]/)[0];
    if (stripPrefix && path.startsWith(stripPrefix)) path = path.slice(stripPrefix.length);
    const file = join(dist, path.replace(/^\//, ""));
    if (!existsSync(file)) throw new Error(`${page} references ${ref}, which is not in ${dist}`);
    files.push({ ref, raw: readFileSync(file).length, gzip: gzipSize(file) });
  }
  return {
    page,
    files,
    raw: files.reduce((sum, f) => sum + f.raw, 0),
    gzip: files.reduce((sum, f) => sum + f.gzip, 0),
  };
}

function main() {
  const args = process.argv.slice(2);
  const app = args.find((a) => !a.startsWith("--"));
  const budgets = JSON.parse(readFileSync(BUDGET_FILE, "utf8"));
  const config = budgets.apps?.[app];
  if (!config) {
    console.error(`usage: check-bundle-budget.mjs <${Object.keys(budgets.apps).join("|")}> [--json]`);
    process.exit(2);
  }
  const dist = resolve(UI_ROOT, config.dist);
  const results = Object.entries(config.pages).map(([page, budget]) => ({
    ...measurePage(dist, page, config.stripPrefix),
    budgetKb: budget.budgetKb,
    baselineKb: budget.baselineKb,
  }));

  if (args.includes("--json")) {
    console.log(JSON.stringify(results, null, 2));
  } else {
    for (const r of results) {
      console.log(
        `${r.page.padEnd(16)} ${String(kb(r.gzip)).padStart(7)} KB gz  (${kb(r.raw)} KB raw, ${r.files.length} files)` +
          `  budget ${r.budgetKb} KB, phase-0 baseline ${r.baselineKb} KB`,
      );
      for (const f of [...r.files].sort((a, b) => b.gzip - a.gzip)) {
        console.log(`    ${String(kb(f.gzip)).padStart(7)} KB  ${f.ref}`);
      }
    }
  }

  const over = results.filter((r) => kb(r.gzip) > r.budgetKb);
  for (const r of over) {
    const msg = `${app}/${r.page}: first load is ${kb(r.gzip)} KB gz, over its budget of ${r.budgetKb} KB (ui/bundle-budget.json)`;
    console.error(process.env.GITHUB_ACTIONS ? `::error::${msg}` : msg);
  }
  process.exit(over.length ? 1 : 0);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
