/* ──────────────────────────────────────────────
   Package boundaries (frontend unification plan §3.1)
   Apps may import packages; packages never import apps; @eddi/core imports
   neither React nor @eddi/ui; @eddi/ui makes no API call (no @eddi/core, no
   fetch, no window.__EDDI_*__); and no import cycle, inside a package or across
   packages. ESLint (`sharedPackage` in ui/eslint.config.base.js) catches the
   first three at edit time; only this test sees a cycle.

   It reads every package under ui/packages/, not only this one, so a package
   added later is covered without being listed here.
   ────────────────────────────────────────────── */

import { readdirSync, readFileSync, statSync, existsSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { describe, expect, it } from "vitest";

// Vitest runs from ui/packages/core.
const PACKAGES_ROOT = resolve(process.cwd(), "..");

interface Module {
  file: string;
  /** The package's npm name, e.g. "@eddi/core". */
  pkg: string;
  imports: string[];
}

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "node_modules" ? [] : sourceFiles(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

/** Import and re-export specifiers, including `import type` and dynamic imports. */
function specifiers(source: string): string[] {
  const found: string[] = [];
  const patterns = [
    /(?:^|\n)\s*(?:import|export)\s[^;]*?\sfrom\s+["']([^"']+)["']/g,
    /(?:^|\n)\s*import\s+["']([^"']+)["']/g,
    /import\(\s*["']([^"']+)["']\s*\)/g,
  ];
  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      if (match[1]) found.push(match[1]);
    }
  }
  return found;
}

function loadPackages(): { modules: Module[]; entries: Map<string, string> } {
  const modules: Module[] = [];
  const entries = new Map<string, string>();
  for (const dir of readdirSync(PACKAGES_ROOT)) {
    const manifest = join(PACKAGES_ROOT, dir, "package.json");
    if (!existsSync(manifest)) continue;
    const { name } = JSON.parse(readFileSync(manifest, "utf8")) as { name: string };
    const src = join(PACKAGES_ROOT, dir, "src");
    entries.set(name, join(src, "index.ts"));
    for (const file of sourceFiles(src)) {
      modules.push({ file, pkg: name, imports: specifiers(readFileSync(file, "utf8")) });
    }
  }
  return { modules, entries };
}

function resolveRelative(from: string, spec: string, known: Set<string>): string | null {
  const base = resolve(dirname(from), spec);
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`, join(base, "index.ts")]) {
    if (known.has(candidate)) return candidate;
  }
  return null;
}

/** Every cycle a depth-first walk meets, each as the path that closes it. */
function findCycles(graph: Map<string, string[]>): string[][] {
  const cycles: string[][] = [];
  const state = new Map<string, "visiting" | "done">();
  const stack: string[] = [];
  const visit = (node: string) => {
    state.set(node, "visiting");
    stack.push(node);
    for (const next of graph.get(node) ?? []) {
      if (state.get(next) === "visiting") {
        cycles.push(stack.slice(stack.indexOf(next)).concat(next));
      } else if (!state.has(next)) {
        visit(next);
      }
    }
    stack.pop();
    state.set(node, "done");
  };
  for (const node of graph.keys()) if (!state.has(node)) visit(node);
  return cycles;
}

/** A network call from source: fetch(), sendBeacon(), an XHR, EventSource or WebSocket. */
const API_CALL = /\b(?:fetch|sendBeacon)\s*\(|\bnew\s+(?:XMLHttpRequest|EventSource|WebSocket)\b/;

const { modules, entries } = loadPackages();
const known = new Set(modules.map((m) => m.file));

describe("package boundaries", () => {
  it("finds the packages it guards", () => {
    expect([...entries.keys()]).toContain("@eddi/core");
    expect(modules.some((m) => m.file.endsWith(join("core", "src", "index.ts")))).toBe(true);
  });

  it("never imports an app", () => {
    const offenders = modules.flatMap((m) =>
      m.imports
        .filter((spec) => spec.startsWith("@/") || /(^|\/)(manager|chat|workforce)\//.test(spec))
        .map((spec) => `${relative(PACKAGES_ROOT, m.file)} → ${spec}`),
    );
    expect(offenders).toEqual([]);
  });

  it("keeps @eddi/core free of React and the UI packages", () => {
    const offenders = modules
      .filter((m) => m.pkg === "@eddi/core")
      .flatMap((m) =>
        m.imports
          .filter((spec) => /^(react|react-dom)(\/|$)/.test(spec) || /^@eddi\/(ui|conversation)(\/|$)/.test(spec))
          .map((spec) => `${relative(PACKAGES_ROOT, m.file)} → ${spec}`),
      );
    expect(offenders).toEqual([]);
  });

  it("finds @eddi/ui", () => {
    expect([...entries.keys()]).toContain("@eddi/ui");
  });

  it("keeps @eddi/ui free of API calls and server globals", () => {
    // @eddi/ui sits below @eddi/core's callers and @eddi/conversation: what
    // the server says reaches it as props or options from the app.
    const ui = modules.filter((m) => m.pkg === "@eddi/ui");
    const offenders = ui.flatMap((m) => {
      const found = m.imports
        .filter((spec) => /^@eddi\/(core|conversation)(\/|$)/.test(spec))
        .map((spec) => `${relative(PACKAGES_ROOT, m.file)} → ${spec}`);
      const source = readFileSync(m.file, "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/(^|[^:])\/\/[^\n]*/g, "$1");
      const call = API_CALL.exec(source);
      if (call) found.push(`${relative(PACKAGES_ROOT, m.file)}: ${call[0]}`);
      if (/__EDDI_\w*__/.test(source)) found.push(`${relative(PACKAGES_ROOT, m.file)}: window.__EDDI_*__`);
      return found;
    });
    expect(ui.length).toBeGreaterThan(0);
    expect(offenders).toEqual([]);
  });

  it("has no import cycle", () => {
    // Edges: relative imports inside a package, and `@eddi/x` imports to that
    // package's entry point.
    const graph = new Map<string, string[]>();
    for (const m of modules) {
      const targets: string[] = [];
      for (const spec of m.imports) {
        if (spec.startsWith(".")) {
          const target = resolveRelative(m.file, spec, known);
          if (target) targets.push(target);
        } else {
          const pkg = /^(@eddi\/[^/]+)/.exec(spec)?.[1];
          const entry = pkg ? entries.get(pkg) : undefined;
          if (entry && known.has(entry)) targets.push(entry);
        }
      }
      graph.set(m.file, targets);
    }

    const cycles = findCycles(graph).map((loop) => loop.map((f) => relative(PACKAGES_ROOT, f)).join(" → "));

    expect(cycles).toEqual([]);
  });

  it("can fail: it finds two files importing each other", () => {
    const a = resolve("/p/a.ts");
    const b = resolve("/p/b.ts");
    const c = resolve("/p/c.ts");
    const files = new Set([a, b, c]);
    expect(resolveRelative(a, "./b", files)).toBe(b);

    expect(findCycles(new Map([[a, [b]], [b, [a]], [c, [a]]]))).toEqual([[a, b, a]]);
    expect(findCycles(new Map([[a, [b]], [b, [c]], [c, []]]))).toEqual([]);
    expect(API_CALL.test("const r = await fetch(url);")).toBe(true);
    expect(API_CALL.test("new EventSource(u)")).toBe(true);
    expect(API_CALL.test("prefetch the font")).toBe(false);
    expect(specifiers('import type { X } from "./b";\nexport * from "./c";\nconst m = import("./d");')).toEqual([
      "./b",
      "./c",
      "./d",
    ]);
  });
});
