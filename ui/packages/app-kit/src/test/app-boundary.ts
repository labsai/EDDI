import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it, expect } from "vitest";

/**
 * The boundary between the two signed-in apps, checked over resolved paths:
 * the Manager never imports Workforce code and Workforce never imports the
 * Manager's (frontend unification plan, phase 5). What both need lives in a
 * package. ESLint (`appBoundary` in ui/eslint.config.base.js) flags the same at
 * edit time from the specifier; this resolves every relative specifier, so a
 * path that only reaches the other app after `..` segments is caught too.
 */

/** ui/ — this file is ui/packages/app-kit/src/test/app-boundary.ts. */
const UI_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../../..");

const APP_PACKAGES: Record<string, string> = { manager: "eddi-manager", workforce: "eddi-workforce" };

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "node_modules" ? [] : walk(full);
    return /\.(ts|tsx|mts|js|mjs)$/.test(entry.name) ? [full] : [];
  });
}

const SPECIFIER =
  /(?:^|\n)\s*(?:import|export)\s[^;]*?\sfrom\s+["']([^"']+)["']|(?:^|\n)\s*import\s+["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)|vi\.mock\(\s*["']([^"']+)["']/g;

/** `file → spec` for every import in `files` that lands in the `other` app. */
export function importsInto(
  other: string,
  files: string[],
  read: (file: string) => string = (file) => readFileSync(file, "utf8"),
): string[] {
  const otherDir = join(UI_ROOT, other) + sep;
  const pkg = APP_PACKAGES[other];
  const found: string[] = [];
  for (const file of files) {
    for (const m of read(file).matchAll(SPECIFIER)) {
      const spec = m[1] ?? m[2] ?? m[3] ?? m[4]!;
      const intoOther = spec.startsWith(".")
        ? resolve(dirname(file), spec).startsWith(otherDir)
        : spec === pkg || spec.startsWith(`${pkg}/`);
      if (intoOther) found.push(`${relative(UI_ROOT, file).split(sep).join("/")} → ${spec}`);
    }
  }
  return found;
}

/**
 * The suite for one app: none of its files (sources, tests, E2E specs, configs
 * in `dirs`) imports the other app.
 */
export function describeAppBoundary(app: "manager" | "workforce", dirs: string[] = ["src", "e2e"]) {
  const other = app === "manager" ? "workforce" : "manager";
  const appDir = join(UI_ROOT, app);
  const files = dirs.flatMap((d) => walk(join(appDir, d)));

  describe(`${app} never imports ${other} code`, () => {
    it("finds the files it checks", () => {
      expect(files.length).toBeGreaterThan(10);
    });

    it(`has no import that resolves into ui/${other}`, () => {
      expect(importsInto(other, files)).toEqual([]);
    });

    it("can fail: it sees a relative path and the package name, and nothing else", () => {
      const probe = join(appDir, "src", "components", "probe.tsx");
      const source = [
        `import { App } from "../../../${other}/src/app";`,
        `import x from "${APP_PACKAGES[other]}/thing";`,
        `import { Card } from "./workforce/card";`,
        `import { Avatar } from "@eddi/app-kit/components/workforce/advisor-avatar";`,
      ].join("\n");
      expect(importsInto(other, [probe], () => source).map((s) => s.split(" → ")[1])).toEqual([
        `../../../${other}/src/app`,
        `${APP_PACKAGES[other]}/thing`,
      ]);
    });
  });
}
