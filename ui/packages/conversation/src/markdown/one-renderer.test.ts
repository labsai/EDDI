/* ──────────────────────────────────────────────
   One markdown renderer (frontend unification plan §2.5)

   Agent and model text is untrusted: images in it must become links (no
   beacons), raw HTML may only pass rehype-sanitize, KaTeX runs with
   `trust: false`. That policy lives in ONE place — `MarkdownContent` in this
   directory — and this test fails on any other file in the UIs that imports
   `react-markdown` (or its GFM and raw-HTML plugins) directly, which would
   render markdown under rules of its own.

   It reads every app and package under ui/, test files included (a test that
   renders markdown its own way pins a policy nobody ships). There are no
   exceptions: a surface that needs different rules passes options
   (`escapeRawHtml` for third-party release notes, say).
   ────────────────────────────────────────────── */

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { describe, expect, it } from "vitest";

// Vitest runs from ui/packages/conversation.
const UI_ROOT = resolve(process.cwd(), "..", "..");
const ROOTS = ["manager/src", "manager/e2e", "chat/src", "packages"];
/** The renderer itself, which is the one place allowed to import it. */
const RENDERER_DIR = ["packages", "conversation", "src", "markdown"].join(sep);
const SKIP_DIRS = new Set(["node_modules", "dist", ".worktrees", "coverage", "reports"]);

/** The modules that render markdown. Importing them is rendering it. */
const RENDERER_MODULES = ["react-markdown", "remark-gfm", "rehype-raw", "rehype-sanitize", "rehype-katex"];

function sourceFiles(dir: string): string[] {
  let names: string[];
  try {
    names = readdirSync(dir);
  } catch {
    return [];
  }
  return names.flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return SKIP_DIRS.has(name) ? [] : sourceFiles(path);
    return /\.(ts|tsx|js|jsx|mjs)$/.test(name) ? [path] : [];
  });
}

function importsRenderer(source: string): string | null {
  // Cheap pre-check: almost no file names any of them.
  if (!RENDERER_MODULES.some((mod) => source.includes(mod))) return null;
  for (const mod of RENDERER_MODULES) {
    const escaped = mod.replace(/[.*+?^${}()|[\]\\-]/g, "\\$&");
    const pattern = new RegExp(
      `(?:from\\s+|import\\s*\\(\\s*|import\\s+|require\\(\\s*)["']${escaped}(?:/[^"']*)?["']`,
    );
    if (pattern.test(source)) return mod;
  }
  return null;
}

describe("one markdown renderer", () => {
  it("is the only place in the UIs that imports react-markdown or its plugins", () => {
    const offenders: string[] = [];
    let scanned = 0;
    for (const root of ROOTS) {
      const files = sourceFiles(join(UI_ROOT, root));
      // A root that moved would make this test pass by reading nothing.
      expect(files.length, `${root} has no sources`).toBeGreaterThan(0);
      scanned += files.length;
      for (const file of files) {
        const rel = relative(UI_ROOT, file);
        if (rel.startsWith(RENDERER_DIR + sep)) continue;
        const mod = importsRenderer(readFileSync(file, "utf8"));
        if (mod) offenders.push(`${rel.split(sep).join("/")} imports ${mod}`);
      }
    }
    expect(scanned).toBeGreaterThan(500);
    expect(offenders).toEqual([]);
  }, 30_000);

  it("sees an import when there is one", () => {
    expect(importsRenderer('import ReactMarkdown from "react-markdown";')).toBe("react-markdown");
    expect(importsRenderer("const m = await import('rehype-raw');")).toBe("rehype-raw");
    expect(importsRenderer('import type { Components } from "react-markdown";')).toBe("react-markdown");
    expect(importsRenderer('import { MarkdownContent } from "@eddi/conversation";')).toBeNull();
  });
});
