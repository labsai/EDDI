import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

/**
 * No user-visible string may name EDDI: a white-labelled deployment shows its
 * own product name instead (white-label plan, §4.4 "Strings").
 *
 * The product name reaches every string as `{{productName}}` — an i18next
 * default variable set from the brand (`src/i18n/config.ts`). A literal "EDDI"
 * in a locale value, in a `t()` fallback, or in JSX text or a text attribute
 * (`alt`, `aria-label`, `title`, `placeholder`) would show EDDI under any brand,
 * so this test fails on one.
 *
 * Deliberately NOT covered, because they are not product mentions:
 *
 * - Technical identifiers, which are contracts and never renamed: `EDDI_*`
 *   environment variables, `X-EDDI-*` / `X-Eddi-*` headers, `eddi.*`
 *   properties, `eddi://` URIs, docker images. Most never match `\bEDDI\b`
 *   anyway (case, or a word character after the name); the rest are stripped
 *   by {@link TECHNICAL} before matching.
 * - Console log prefixes and storage keys — never shown in the UI.
 * - Comments.
 */

const repoRoot = resolve(__dirname, "../..");
const LOCALES_DIR = join(repoRoot, "src/i18n/locales");
const EDDI = /\bEDDI\b/;

/** Technical identifiers that contain the name but are not product mentions. */
const TECHNICAL = /\bX-EDDI-[\w-]+|\bEDDI_[A-Z0-9_]+|labsai\/EDDI\b/g;

/**
 * Locale keys that keep EDDI on purpose, each with its reason. Exact keys, never
 * a prefix: an exemption must be looked at string by string.
 */
const ALLOWED_KEYS: Record<string, string> = {
  "about.poweredBy": "the Powered-by notice names EDDI by design; the brand's poweredBy toggle hides it (R3)",
  "about.license": "the Apache 2.0 notice beside it names the licensed software",
  "webhook.schemeHintEddi":
    "describes the eddi-v1 signature scheme next to its X-Eddi-Signature header — a protocol name, not the product",
  "cluster.metrics.text": "names the Grafana dashboard as it is titled (\"EDDI Cluster\"), so an operator can find it",
};

type Json = Record<string, unknown>;

function flatten(obj: Json, prefix = "", out: Record<string, unknown> = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v as Json, key, out);
    else out[key] = v;
  }
  return out;
}

function namesEddi(text: string): boolean {
  return EDDI.test(text.replace(TECHNICAL, ""));
}

/** Every `locale:key` whose value names EDDI outside {@link ALLOWED_KEYS}. */
function localeOffenders(locales: Record<string, Json>): string[] {
  const found: string[] = [];
  for (const [code, json] of Object.entries(locales)) {
    for (const [key, value] of Object.entries(flatten(json))) {
      if (key in ALLOWED_KEYS) continue;
      if (typeof value === "string" && namesEddi(value)) found.push(`${code}: ${key} = ${value}`);
    }
  }
  return found;
}

/** Remove comments, keeping line breaks so reported line numbers stay right. */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, " "))
    .replace(/(^|[\s;{}(),])\/\/[^\n]*/g, "$1");
}

const PATTERNS: { kind: string; regex: RegExp }[] = [
  // t("key", "Fallback") and t("key", { defaultValue: "Fallback" }), possibly wrapped.
  // The key is captured too, for ALLOWED_KEYS.
  {
    kind: "t() fallback",
    regex: /\bt\(\s*(["'`])(?<key>[^"'`]*)\1\s*,\s*(["'`])((?:\\.|(?!\3)[^\\])*)\3/g,
  },
  { kind: "defaultValue", regex: /\bdefaultValue:\s*(["'`])((?:\\.|(?!\1)[^\\])*)\1/g },
  // Error messages: the operator screens show them to the admin verbatim.
  { kind: "Error message", regex: /\bnew Error\(\s*(["'`])((?:\\.|(?!\1)[^\\])*)\1/g },
  // JSX text between tags. Not after `=>` or `->`, and never across a quote, so
  // an arrow function or a `<hex>` inside a string does not read as a tag.
  { kind: "JSX text", regex: /(?<![=-])>([^<>{}"]*)</g },
  // Text attributes, quoted or as a braced literal.
  {
    kind: "attribute",
    regex: /\b(?:alt|aria-label|title|placeholder)=(?:\{\s*)?(["'`])((?:\\.|(?!\1)[^\\])*)\1/g,
  },
];

/** Every `file:line` in `source` whose user-visible text names EDDI. */
function sourceOffenders(rel: string, source: string): string[] {
  const code = stripComments(source);
  const found: string[] = [];
  for (const { kind, regex } of PATTERNS) {
    for (const match of code.matchAll(regex)) {
      // The text is always the last capture group.
      const text = match[match.length - 1] ?? "";
      if (!namesEddi(text)) continue;
      if (match.groups?.key && match.groups.key in ALLOWED_KEYS) continue;
      const line = code.slice(0, match.index).split("\n").length;
      found.push(`${rel}:${line} (${kind}): ${text.trim()}`);
    }
  }
  return found;
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      // Tests assert on the default (EDDI) brand; mocks are sample data.
      if (["__tests__", "test", "locales"].includes(entry.name)) return [];
      return sourceFiles(path);
    }
    return /\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

function loadLocales(): Record<string, Json> {
  return Object.fromEntries(
    readdirSync(LOCALES_DIR)
      .filter((f) => f.endsWith(".json"))
      .map((f) => [f.replace(/\.json$/, ""), JSON.parse(readFileSync(join(LOCALES_DIR, f), "utf8")) as Json]),
  );
}

describe("no EDDI in user-visible strings", () => {
  it("no locale value names EDDI; product mentions use {{productName}}", () => {
    expect(
      localeOffenders(loadLocales()),
      "Use {{productName}} for the product, 'server version' for an engine-version gate, or allow-list a technical name with its reason",
    ).toEqual([]);
  });

  it("no t() fallback, JSX text or text attribute names EDDI", () => {
    const offenders = sourceFiles(join(repoRoot, "src")).flatMap((file) => {
      const rel = relative(repoRoot, file).split("\\").join("/");
      return sourceOffenders(rel, readFileSync(file, "utf8"));
    });
    expect(offenders, "Use {{productName}} in the fallback, or useBrand().productName in JSX").toEqual([]);
  });

  it("every allow-listed key still exists in en.json", () => {
    // A stale exemption hides nothing today but would silently cover a key
    // re-added later with a different meaning.
    const en = flatten(loadLocales().en!);
    expect(Object.keys(ALLOWED_KEYS).filter((key) => !(key in en))).toEqual([]);
  });

  it("detects what it forbids", () => {
    // A guard whose patterns match nothing passes vacuously.
    expect(localeOffenders({ xx: { a: { b: "Welcome to EDDI" } } })).toHaveLength(1);
    expect(localeOffenders({ xx: { about: { poweredBy: "Powered by EDDI" } } })).toEqual([]);
    expect(localeOffenders({ xx: { k: "Set EDDI_VAULT_MASTER_KEY and X-EDDI-Space" } })).toEqual([]);
    expect(localeOffenders({ xx: { k: "Set EDDI_VAULT_MASTER_KEY, then restart EDDI" } })).toHaveLength(1);

    expect(sourceOffenders("a.tsx", 't("k", "Welcome to EDDI")')).toHaveLength(1);
    expect(sourceOffenders("a.tsx", 't(\n  "k",\n  "Can\'t reach EDDI.",\n)')).toHaveLength(1);
    expect(sourceOffenders("a.tsx", 't("k", { defaultValue: "EDDI rocks" })')).toHaveLength(1);
    expect(sourceOffenders("a.tsx", "<p>Welcome to EDDI</p>")).toHaveLength(1);
    expect(sourceOffenders("a.tsx", '<img alt="EDDI" />')).toHaveLength(1);
    expect(sourceOffenders("a.tsx", "<svg aria-label={'EDDI'} />")).toHaveLength(1);
    expect(sourceOffenders("a.ts", 'throw new Error("This EDDI deployment is down");')).toHaveLength(1);
    expect(sourceOffenders("a.ts", "throw new Error(`EDDI said ${x}`);")).toHaveLength(1);
    expect(sourceOffenders("a.ts", 'throw new Error("This deployment is down");')).toEqual([]);

    expect(sourceOffenders("a.tsx", 't("k", "Welcome to {{productName}}")')).toEqual([]);
    expect(sourceOffenders("a.tsx", "// EDDI does this\n/* and EDDI that */")).toEqual([]);
    expect(sourceOffenders("a.tsx", "{/* EDDI matches by prefix */}")).toEqual([]);
    expect(sourceOffenders("a.tsx", 'headers: { "X-EDDI-Space": id }')).toEqual([]);
    expect(sourceOffenders("a.tsx", "<img alt={productName} />")).toEqual([]);
    expect(sourceOffenders("a.tsx", 't("about.poweredBy", "Powered by EDDI")')).toEqual([]);
    expect(sourceOffenders("a.tsx", "const f = (x) => x.EDDI < limit;")).toEqual([]);
  });
});
