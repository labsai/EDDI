/**
 * Fail CI when the locale files drift from the code.
 *
 * There was already a parity test (`src/i18n/__tests__/config.test.ts`) checking
 * that every non-English file carries every key `en.json` has. It only ever
 * asked half the question, and it is the other half that rotted: nothing checked
 * that `en.json` carries every key the CODE asks for.
 *
 * The result, measured before this script existed: 349 distinct keys were passed
 * to `t()` and present in no locale file at all. They rendered their inline
 * English fallback in every language — the whole Workforce namespace (233 keys),
 * the Analytics screen (50), and a scattering elsewhere. The app advertised 11
 * languages and shipped English for those screens, silently, because a fallback
 * looks exactly like a translation to anyone reading the code.
 *
 * Three checks, all failing:
 *
 *  1. MISSING — a key `t()` asks for that `en.json` does not define.
 *  2. PARITY  — a key `en.json` defines that some other locale does not.
 *  3. ORPHAN  — a key some locale defines that `en.json` does not, i.e. a
 *     translation left behind when the English key was removed.
 *  4. COLLIDING — one key passed to `t()` with two different English defaults.
 *     Only one can live in `en.json`, so the other call site silently renders
 *     text meant for somewhere else. 24 of these already existed; the list below
 *     freezes that set so it can shrink but never grow.
 *
 * Deliberately NOT checked: keys present in `en.json` but never referenced.
 * Roughly two dozen call sites build keys dynamically (`t(\`groups.style.${id}\`)`),
 * and static analysis cannot see through them, so an unused-key check would fire
 * on live strings and get muted — which is how the parity test acquired a
 * `variables.` escape hatch and stopped noticing 34 real gaps.
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const LOCALES_DIR = path.join(ROOT, "src/i18n/locales");
const SRC_DIR = path.join(ROOT, "src");

/** i18next plural suffixes — `key_one` satisfies a `t("key")` reference. */
const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "locales") continue;
      walk(full, out);
    } else if (/\.(ts|tsx)$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

function flatten(obj, prefix = "", out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v, key, out);
    else out[key] = v;
  }
  return out;
}

const isTestFile = (f) => /__tests__|\.test\.|[\\/]test[\\/]/.test(f);

/**
 * Every key referenced by a string literal passed to `t()` or `i18nKey=`.
 *
 * Scans each file WHOLE rather than line by line. Prettier wraps long calls, so
 * `t(\n  "some.key",\n  "Default",\n)` is extremely common here — and a per-line
 * scan cannot match `t(` against a key on the next line. Measured when this was
 * fixed: the line-based version saw 2,955 keys where the file-based one sees
 * 3,287. All 332 it missed happened to be present in `en.json`, so the gate was
 * green while blind to a third of the codebase; a new wrapped key would simply
 * not have been checked.
 *
 * Line numbers come from the match offset, so failures stay clickable.
 */
export function collectUsedKeys(files, read = (f) => fs.readFileSync(f, "utf8")) {
  const used = new Map(); // key -> first "file:line"

  for (const file of files) {
    if (isTestFile(file)) continue;
    const src = read(file);
    // `\s*` spans newlines, so this matches wrapped calls as well as inline ones.
    const patterns = [
      /\bt\(\s*(["'])([A-Za-z0-9_.-]+)\1/g,
      /\bi18nKey=\s*(["'])([A-Za-z0-9_.-]+)\1/g,
    ];
    for (const re of patterns) {
      for (const m of src.matchAll(re)) {
        const key = m[2];
        if (!key.includes(".") || used.has(key)) continue;
        // Count newlines before the match rather than tracking them as we go —
        // simpler, and this runs once per key, not once per character.
        const line = src.slice(0, m.index).split("\n").length;
        used.set(key, `${file}:${line}`);
      }
    }
  }
  return used;
}

/** A key is satisfied by an exact match or by any of its plural variants. */
export function isSatisfied(key, definedKeys) {
  if (definedKeys.has(key)) return true;
  for (const suffix of ["zero", "one", "two", "few", "many", "other"]) {
    if (definedKeys.has(`${key}_${suffix}`)) return true;
  }
  return false;
}

/**
 * A locale may legitimately carry plural forms English does not (`ar` has six
 * categories, `es`/`fr`/`pt` have `many`). Those are not orphans, so an extra
 * key is only reported when its base form is absent from English too.
 */
export function isLegitimatePluralVariant(key, enKeys) {
  const base = key.replace(PLURAL_SUFFIX, "");
  return base !== key && isSatisfied(base, enKeys);
}

/**
 * Keys already used with conflicting English defaults when this check landed.
 *
 * Each is a latent bug — whichever default reached `en.json` first wins for
 * every call site — but fixing one is a product decision about which wording is
 * right, not a mechanical rename. Frozen here so the count can only fall.
 * Removing an entry is the fix; adding one is not allowed.
 */
export const KNOWN_COLLISIONS = new Set([
  "Workforce.agentEditor.a2aEnabled",
  "agents.deploySuccess",
  "common.copied",
  "common.copy",
  "common.error",
  "common.loading",
  "common.noResults",
  "common.retry",
  "common.saving",
  "common.showLess",
  "common.showMore",
  "contentEditor.expand",
  "conversations.steps",
  "editor.invalidJson",
  "groupWizard.maxTurns",
  "groupWizard.selectGroup",
  "hitl.awaitingHuman",
  "llmEditor.cascadeModelName",
  "llmEditor.summarizationPrompt",
  "memories.title",
  "properties.title",
  "secrets.keyNamePlaceholder",
  "secrets.vaultNotConfigured",
  "userConversations.title",
]);

/**
 * Given the index of a `{`, return the object literal it opens, brace-balanced
 * and quote-aware, or `null` if it is never closed.
 */
function readObjectLiteral(src, open) {
  let depth = 0;
  let quote = null;
  let escaped = false;
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    if (quote) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") quote = c;
    else if (c === "{") depth++;
    else if (c === "}" && --depth === 0) return src.slice(open, i + 1);
  }
  return null;
}

/** The `defaultValue:` string literal in an options object, if it has one. */
function defaultValueIn(options) {
  // Anchored on the property name rather than the enclosing braces, so nested
  // option objects before it (`{ interpolation: { … }, defaultValue: "…" }`)
  // are irrelevant. A default built from a template literal, or one containing
  // an escaped quote, is skipped rather than half-captured: this only has to
  // tell two DIFFERENT defaults apart, not reproduce either.
  const m = /\bdefaultValue\s*:\s*(["'])((?:(?!\1)[^\n])*)\1/.exec(options);
  return m ? m[2] : null;
}

/** Map of key -> the distinct English defaults it is called with. */
export function collectDefaults(files, read = (f) => fs.readFileSync(f, "utf8")) {
  const byKey = new Map();
  const add = (key, value) => {
    if (!key.includes(".")) return;
    if (!byKey.has(key)) byKey.set(key, new Set());
    byKey.get(key).add(value);
  };

  for (const file of files) {
    if (isTestFile(file)) continue;
    const src = read(file);

    // Two shapes carry an English default, and both are used in this codebase:
    //
    //   t("key", "Default")                       — positional
    //   t("key", { count, defaultValue: "…" })    — object form
    //
    // The positional form is a plain literal closed by the quote it opened
    // with; anything fancier is skipped, same rationale as `defaultValueIn`.
    const positional = /\bt\(\s*(["'])([A-Za-z0-9_.-]+)\1\s*,\s*(["'])((?:(?!\3)[^\n])*)\3/g;
    for (const m of src.matchAll(positional)) add(m[2], m[4]);

    // The object form is brace-matched rather than pattern-matched. A regex
    // bounded by `[^}]*?` stops at the first `}` it meets, so an option object
    // holding a nested one — `{ interpolation: { escapeValue: false },
    // defaultValue: "…" }` — slipped past the scan entirely and its key was
    // silently left out of the collision report.
    const objectForm = /\bt\(\s*(["'])([A-Za-z0-9_.-]+)\1\s*,\s*(?=\{)/g;
    for (const m of src.matchAll(objectForm)) {
      const options = readObjectLiteral(src, m.index + m[0].length);
      if (!options) continue;
      const value = defaultValueIn(options);
      if (value !== null) add(m[2], value);
    }
  }
  return byKey;
}

export function check({ localesDir = LOCALES_DIR, srcDir = SRC_DIR } = {}) {
  const files = fs.readdirSync(localesDir).filter((f) => f.endsWith(".json"));
  const locales = new Map(
    files.map((f) => [
      path.basename(f, ".json"),
      flatten(JSON.parse(fs.readFileSync(path.join(localesDir, f), "utf8"))),
    ]),
  );

  const en = locales.get("en");
  if (!en) throw new Error("en.json not found");
  const enKeys = new Set(Object.keys(en));

  const used = collectUsedKeys(walk(srcDir));

  const missing = [...used]
    .filter(([key]) => !isSatisfied(key, enKeys))
    .map(([key, where]) => ({ key, where }));

  const parity = [];
  const orphans = [];
  for (const [code, table] of locales) {
    if (code === "en") continue;
    const keys = new Set(Object.keys(table));
    for (const key of enKeys) {
      if (!keys.has(key)) parity.push({ code, key });
    }
    for (const key of keys) {
      if (!enKeys.has(key) && !isLegitimatePluralVariant(key, enKeys)) {
        orphans.push({ code, key });
      }
    }
  }

  const collisions = [...collectDefaults(walk(srcDir))]
    .filter(([key, defaults]) => defaults.size > 1 && !KNOWN_COLLISIONS.has(key))
    .map(([key, defaults]) => ({ key, defaults: [...defaults] }));

  return {
    missing,
    parity,
    orphans,
    collisions,
    localeCount: locales.size,
    enKeyCount: enKeys.size,
  };
}

function main() {
  const { missing, parity, orphans, collisions, localeCount, enKeyCount } = check();
  let failed = false;

  if (missing.length) {
    failed = true;
    console.error(
      `\n::error::${missing.length} key(s) are passed to t() but absent from en.json.`,
    );
    console.error("They render their inline English fallback in EVERY language.\n");
    for (const { key, where } of missing.slice(0, 40)) {
      console.error(`  ${key}\n      ${path.relative(ROOT, where)}`);
    }
    if (missing.length > 40) console.error(`  …and ${missing.length - 40} more`);
    console.error("\nFix: add each to src/i18n/locales/en.json, then translate into all 10 others.");
  }

  if (parity.length) {
    failed = true;
    const byLocale = {};
    for (const { code, key } of parity) (byLocale[code] ??= []).push(key);
    console.error(`\n::error::${parity.length} key(s) in en.json are missing from other locales.\n`);
    for (const [code, keys] of Object.entries(byLocale)) {
      console.error(`  ${code}: ${keys.length} missing — e.g. ${keys.slice(0, 3).join(", ")}`);
    }
  }

  if (orphans.length) {
    failed = true;
    const byLocale = {};
    for (const { code, key } of orphans) (byLocale[code] ??= []).push(key);
    console.error(`\n::error::${orphans.length} key(s) exist in a locale but not in en.json.\n`);
    for (const [code, keys] of Object.entries(byLocale)) {
      console.error(`  ${code}: ${keys.join(", ")}`);
    }
    console.error("\nThese are leftovers from a removed English key. Delete them.");
  }

  if (collisions.length) {
    failed = true;
    console.error(
      `\n::error::${collisions.length} key(s) are passed to t() with more than one English default.`,
    );
    console.error("Only one can reach en.json; the other call site renders the wrong text.\n");
    for (const { key, defaults } of collisions) {
      console.error(`  ${key}`);
      for (const d of defaults) console.error(`      ${JSON.stringify(d)}`);
    }
    console.error("\nFix: give each distinct string its own key.");
  }

  if (failed) process.exit(1);
  console.log(`i18n OK — ${enKeyCount} keys across ${localeCount} locales, no drift.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
