import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

/**
 * No component may name Tailwind's amber palette or the brand's gold as a hex.
 *
 * Amber was both the EDDI brand and the app's caution colour, hard-coded as
 * `amber-N` utilities in over a hundred files, so a white-labelled deployment's
 * colour could not reach them. They now read one of three scales from
 * `src/index.css`:
 *
 * - `brand-N` — accents, selection, primary emphasis; a branded
 *   `/branding/brand.css` redefines it from the brand's primary colour.
 * - `warn-N` — caution, never re-branded: warnings, HITL pauses, pending
 *   states, unsaved markers, in-progress or degraded status.
 * - `ochre-N` — a fixed categorical amber hue, never re-branded and not a
 *   warning: per-type badges, section accents, avatar and syntax colours.
 *
 * This test keeps new code from going back to `amber-N` (which would silently
 * ignore the brand) or to a gold hex literal. `src/index.css` is where the two
 * scales are mapped onto amber, so its custom-property declarations are the one
 * place amber may be named. `warn-N` and `ochre-N` contain no `amber-`, so
 * they pass by construction; the last test pins that.
 *
 * The allow-list is for a use that genuinely cannot take a token. It should stay
 * empty; an entry needs a reason, and it may only ever shrink.
 */

const repoRoot = resolve(__dirname, "../..");
const ROOTS = ["src"];
const SHELLS = ["manage.html", "welcome.html", "workforce.html"];
const SOURCE = /\.(?:[cm]?[jt]sx?|css|html)$/;
/** The theme file that maps brand-N / warn-N / ochre-N onto amber. */
const THEME_FILE = "src/index.css";

const AMBER_UTILITY = /amber-\d/;
/**
 * The brand golds that used to be written out: amber 500 and 400 as hex and as
 * rgb(a), and the loader spinner's gold. A shell may keep the spinner gold only
 * as the fallback of `var(--brand-loader-accent, …)`.
 */
const AMBER_HEX = /#(?:f59e0b|fbbf24|d4a843)\b|rgba?\(\s*245\s*,\s*158\s*,\s*11\b|rgba?\(\s*251\s*,\s*191\s*,\s*36\b/i;
const LOADER_FALLBACK = /var\(--brand-loader-accent,\s*#d4a843\)/gi;

/** `path:line` entries allowed to keep amber, each with its reason. Keep empty. */
const ALLOW_LIST: Record<string, string> = {};

function sourceFiles(dir: string): string[] {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries.flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return SOURCE.test(entry.name) ? [path] : [];
  });
}

function offenders(): string[] {
  const files = [
    ...ROOTS.flatMap((root) => sourceFiles(join(repoRoot, root))),
    ...SHELLS.map((shell) => join(repoRoot, shell)),
  ];
  const found: string[] = [];
  for (const file of files) {
    const rel = relative(repoRoot, file).split("\\").join("/");
    // This file names what it forbids.
    if (rel === "src/__tests__/no-hardcoded-amber.test.ts") continue;
    readFileSync(file, "utf8")
      .split("\n")
      .forEach((line, i) => {
        const key = `${rel}:${i + 1}`;
        if (key in ALLOW_LIST) return;
        // Token definitions in the theme file are where amber is mapped.
        if (rel === THEME_FILE && /^\s*--[\w-]+\s*:/.test(line)) return;
        const text = line.replace(LOADER_FALLBACK, "");
        if (AMBER_UTILITY.test(text) || AMBER_HEX.test(text)) found.push(`${key}: ${line.trim()}`);
      });
  }
  return found;
}

describe("no hard-coded amber", () => {
  it("uses brand-N, warn-N or ochre-N, never an amber-N utility or a gold hex", () => {
    expect(
      offenders(),
      "Use brand-N for accents that should follow a white-label brand, warn-N for caution, ochre-N for a fixed categorical amber hue (see src/index.css)",
    ).toEqual([]);
  });

  it("keeps the allow-list empty", () => {
    expect(Object.keys(ALLOW_LIST)).toEqual([]);
  });

  it("detects what it forbids", () => {
    // A guard whose patterns match nothing passes vacuously.
    expect(AMBER_UTILITY.test("bg-amber-500/10")).toBe(true);
    expect(AMBER_UTILITY.test("dark:text-amber-400")).toBe(true);
    expect(AMBER_UTILITY.test("var(--color-amber-400)")).toBe(true);
    expect(AMBER_UTILITY.test("bg-warn-500/10 text-brand-600")).toBe(false);
    expect(AMBER_UTILITY.test("bg-ochre-500/10 text-ochre-600 dark:text-ochre-400")).toBe(false);
    expect(AMBER_UTILITY.test("color: var(--color-ochre-400)")).toBe(false);
    expect(AMBER_HEX.test('color: "#F59E0B"')).toBe(true);
    expect(AMBER_HEX.test("rgba(245, 158, 11, 0.15)")).toBe(true);
    expect(AMBER_HEX.test("border-top-color: #d4a843;")).toBe(true);
    expect(AMBER_HEX.test("var(--brand-loader-accent, #d4a843)".replace(LOADER_FALLBACK, ""))).toBe(false);
  });
});
