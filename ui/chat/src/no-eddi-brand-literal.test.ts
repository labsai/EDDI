/**
 * No user-visible string may name EDDI: under a deployment brand the widget
 * shows the brand's product name instead (white-label plan §4.5). The Manager
 * has the same guard (ui/manager/src/__tests__/no-eddi-brand-literal.test.ts).
 *
 * Graded: every locale value in src/i18n.ts, and every string literal and JSX
 * text in the widget's source. The Chat routes all copy through t(), so a
 * string literal naming EDDI is either an unbranded default — allow-listed
 * below, each with its reason — or a leak.
 *
 * Deliberately NOT graded: comments; `[eddi-chat]` console prefixes, the
 * `eddi-chat-theme` storage key and `eddi-chat-*` postMessage types (contracts,
 * and lower-case, so `\bEDDI\b` never matches them); tests; and demo-api.ts,
 * the EDDI showcase script, which ChatRoute switches off under a brand.
 */
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { TRANSLATIONS } from "@/i18n";

const ROOT = process.cwd();
const EDDI = /\bEDDI\b/;

/** Files not graded, each with its reason. */
const EXCLUDED_FILES: Record<string, string> = {
  "src/api/demo-api.ts": "EDDI's own showcase script; ChatRoute shows a neutral state instead under a brand",
};

/**
 * Exact `file::literal` pairs that keep EDDI on purpose. Exact, never a
 * pattern: an exemption must be looked at string by string.
 */
const ALLOWED: Record<string, string> = {
  'src/api/brand.ts::"EDDI"': "DEFAULT_BRAND's productName and shortName — the unbranded defaults",
  'src/api/brand.ts::"EDDI Chat"': "UNBRANDED_DOCUMENT_TITLE — the tab title when nothing is branded",
  'src/store/chat-store.tsx::"EDDI"': "DEFAULT_TITLE — the header title when nothing is branded",
  'src/api/chat-config.ts::"is set on the EDDI server. Pass the token by postMessage (?tokenOrigin=) or ChatConfig.authToken instead."':
    "a console warning for the integrator, naming the engine whose property it is",
};

/** Remove comments, keeping line breaks so reported line numbers stay right. */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "))
    .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, "$1");
}

const PATTERNS: { kind: string; regex: RegExp }[] = [
  // Quoted strings on one line, template literals across lines.
  { kind: "string", regex: /"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`/g },
  // JSX text between tags. Not after `=>` or `->`, and never across a quote.
  { kind: "JSX text", regex: /(?<![=-])>([^<>{}"'`]*)</g },
];

/** Every `file:line` in `source` whose literal text names EDDI. */
function sourceOffenders(rel: string, source: string): string[] {
  const code = stripComments(source);
  const found: string[] = [];
  for (const { kind, regex } of PATTERNS) {
    for (const match of code.matchAll(regex)) {
      const text = match[1] ?? match[0];
      if (!EDDI.test(text)) continue;
      if (`${rel}::${text}` in ALLOWED) continue;
      const line = code.slice(0, match.index).split("\n").length;
      found.push(`${rel}:${line} (${kind}): ${text.trim()}`);
    }
  }
  return found;
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "test-utils" ? [] : sourceFiles(path);
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

function relPath(file: string): string {
  return relative(ROOT, file).split("\\").join("/");
}

describe("no EDDI in user-visible strings", () => {
  it("no locale value names EDDI", () => {
    const found = Object.entries(TRANSLATIONS).flatMap(([code, table]) =>
      Object.entries(table)
        .filter(([, value]) => EDDI.test(value))
        .map(([key, value]) => `${code}: ${key} = ${value}`),
    );
    expect(found).toEqual([]);
  });

  it("no string literal or JSX text names EDDI outside the allow-list", () => {
    const offenders = sourceFiles(resolve(ROOT, "src")).flatMap((file) => {
      const rel = relPath(file);
      if (rel in EXCLUDED_FILES) return [];
      return sourceOffenders(rel, readFileSync(file, "utf8"));
    });
    expect(offenders, "Use getBrand().productName (src/api/brand.ts), or allow-list an unbranded default with its reason").toEqual([]);
  });

  it("every allow-list entry still matches something", () => {
    // A stale exemption hides nothing today but would silently cover a
    // string re-added later with a different meaning.
    const stale = Object.keys(ALLOWED).filter((entry) => {
      const [rel, text] = entry.split("::");
      return !stripComments(readFileSync(resolve(ROOT, rel!), "utf8")).includes(text!);
    });
    expect(stale).toEqual([]);
    expect(Object.keys(EXCLUDED_FILES).filter((rel) => !sourceFiles(resolve(ROOT, "src")).map(relPath).includes(rel))).toEqual([]);
  });

  it("detects what it forbids", () => {
    // A guard whose patterns match nothing passes vacuously.
    expect(sourceOffenders("a.tsx", 'document.title = "EDDI Chat";')).toHaveLength(1);
    expect(sourceOffenders("a.tsx", "const t = `${name} — EDDI Chat`;")).toHaveLength(1);
    expect(sourceOffenders("a.tsx", "<p>Welcome to EDDI</p>")).toHaveLength(1);
    expect(sourceOffenders("a.tsx", '<img alt="EDDI" />')).toHaveLength(1);

    expect(sourceOffenders("a.tsx", "// EDDI does this\n/* and EDDI that */")).toEqual([]);
    expect(sourceOffenders("a.tsx", 'console.warn("[eddi-chat] x");')).toEqual([]);
    expect(sourceOffenders("a.tsx", "<img alt={productName} />")).toEqual([]);
    expect(sourceOffenders("src/store/chat-store.tsx", 'export const DEFAULT_TITLE = "EDDI";')).toEqual([]);
  });
});
