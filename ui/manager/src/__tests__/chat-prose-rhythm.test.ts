import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

/**
 * The chat-scale markdown rhythm lives once, in `index.css`.
 *
 * Twelve surfaces render model-written markdown, and each had grown its own
 * hand-rolled subset of the same repairs as arbitrary-variant utilities. The
 * subsets disagreed, and the disagreement was visible: paragraph spacing was
 * 4px on the Workforce board and the typography plugin's 16px on the Manager
 * transcript, so the SAME discussion read with two different rhythms depending
 * on which page it was opened from. `break-words` was on five of the twelve, so
 * a long URL wrapped on some surfaces and was clipped on others.
 *
 * None of those is a per-surface decision, so none of them belongs at a call
 * site. This fails the moment one is restated there again — which is how the
 * drift happened the first time, one reasonable-looking utility at a time.
 *
 * Colour, padding, backgrounds and container overflow are deliberately NOT
 * listed: those really are per-surface, and the operator's darker code
 * background is a real difference rather than a drifted one.
 */

/** Rhythm and wrapping that `index.css` owns for every `.prose` surface. */
const CENTRALIZED = [
  "break-words",
  "[&_pre]:overflow-x-auto",
  "[&_p]:my-1",
  "[&_ul]:my-1",
  "[&_ol]:my-1",
  "[&_p:first-child]:mt-0",
  "[&_p:last-child]:mb-0",
  "[&_hr]:border-border",
];

function tsxFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...tsxFiles(path));
    else if (name.endsWith(".tsx")) out.push(path);
  }
  return out;
}

/** Every `class="…"` string that styles a `prose` container. */
function proseClassLists(source: string): string[] {
  return [...source.matchAll(/"prose\s[^"]*"/g)].map((m) => m[0]);
}

describe("chat prose rhythm is defined once", () => {
  const files = tsxFiles("src");

  it("finds the prose surfaces at all, so the scan cannot pass vacuously", () => {
    const withProse = files.filter((f) => proseClassLists(readFileSync(f, "utf8")).length > 0);
    // Ten files, twelve surfaces — `conversation-viewer` renders three.
    expect(withProse.length).toBeGreaterThanOrEqual(9);
  });

  it("no call site restates the centralized rhythm", () => {
    const offenders: string[] = [];
    for (const file of files) {
      for (const classList of proseClassLists(readFileSync(file, "utf8"))) {
        for (const token of CENTRALIZED) {
          if (classList.includes(token)) offenders.push(`${file}: ${token}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it("index.css actually carries what the call sites gave up", () => {
    // Otherwise the rule above is satisfied by deleting the styling rather than
    // by moving it, and every message loses its wrapping instead of sharing it.
    const css = readFileSync("src/index.css", "utf8");
    expect(css).toContain("overflow-wrap: break-word");
    expect(css).toMatch(/\.prose pre \{[^}]*overflow-x: auto/);
    expect(css).toMatch(/\.prose > :first-child \{[^}]*margin-top: 0/);
    expect(css).toMatch(/\.prose > :last-child \{[^}]*margin-bottom: 0/);
    expect(css).toMatch(/\.prose :is\(p, ul, ol, blockquote\)/);
  });

  it("the rhythm rules stay unlayered, or the plugin's utilities beat them", () => {
    // Tailwind v4 ranks every utility above every layered rule regardless of
    // specificity, so moving these into `@layer components` would silently
    // restore the document-scale spacing they exist to replace.
    const css = readFileSync("src/index.css", "utf8");
    const selector = ".prose :is(p, ul, ol, blockquote)";
    expect(css).toContain(selector);
    const rhythmAt = css.indexOf(selector);
    const openLayers = (css.slice(0, rhythmAt).match(/@layer[^{]*\{/g) ?? []).length;
    const closes = (css.slice(0, rhythmAt).match(/\n\}/g) ?? []).length;
    expect(openLayers).toBeLessThanOrEqual(closes);
  });
});
