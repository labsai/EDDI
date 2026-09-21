import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * Guards the tsconfig the design-system converter reads.
 *
 * `.ds-sync/lib/bundle.mjs` (`tsconfigPathsPlugin`) does not use a JSON5 parser.
 * It strips comments with two regexes and then `JSON.parse`s the result, and if
 * anything throws it returns `null` — which drops alias resolution for the whole
 * bundle instead of failing loudly. The block-comment regex removes everything
 * between a `/`+`*` and the next `*`+`/`, and the `"@/*"` paths key itself
 * contains the opening sequence, so a single stray closing sequence later in the
 * file silently deletes the entire paths object.
 *
 * That is not hypothetical: `tsconfig.design-sync.json` hit it, because its
 * `include` globs supplied the closing delimiter. Hence a dedicated,
 * glob-free `tsconfig.ds-bundle.json` for the converter — and this test.
 */

const repoRoot = resolve(__dirname, "../..");

/** Byte-for-byte the stripping `.ds-sync/lib/bundle.mjs` performs. */
function stripLikeConverter(raw: string): string {
  return raw
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");
}

function readConverterPaths(tsconfigRelPath: string) {
  const raw = readFileSync(resolve(repoRoot, tsconfigRelPath), "utf8");
  const parsed = JSON.parse(stripLikeConverter(raw)) as {
    compilerOptions?: { paths?: Record<string, string[]>; baseUrl?: string };
  };
  return parsed.compilerOptions?.paths;
}

describe("design-sync converter tsconfig", () => {
  const cfg = JSON.parse(
    readFileSync(resolve(repoRoot, ".design-sync/config.json"), "utf8"),
  ) as { tsconfig: string };

  it("survives the converter's comment stripper and still yields paths", () => {
    // A paths-less parse is the silent-failure mode: the plugin returns null and
    // every `@/…` import in the bundle goes unresolved.
    const paths = readConverterPaths(cfg.tsconfig);
    expect(paths, `${cfg.tsconfig} lost its paths to the comment stripper`).toBeDefined();
    expect(Object.keys(paths!).length).toBeGreaterThan(0);
  });

  it("keeps the '@/' wildcard so app imports resolve", () => {
    const paths = readConverterPaths(cfg.tsconfig)!;
    expect(paths["@/*"]).toEqual(["./src/*"]);
  });

  it("maps the operator drawer to the stub, ahead of the wildcard", () => {
    const paths = readConverterPaths(cfg.tsconfig)!;
    const key = "@/components/operator/operator-drawer";
    expect(paths[key]).toEqual(["./.design-sync/stubs/operator-drawer.tsx"]);

    // The converter's resolver returns the first matching rule in declaration
    // order, so the exact key must precede the wildcard or the stub is ignored
    // and the operator subsystem lands in the bundle again.
    const keys = Object.keys(paths);
    expect(keys.indexOf(key)).toBeLessThan(keys.indexOf("@/*"));
  });

  it("has no include globs that could close a block comment", () => {
    const raw = readFileSync(resolve(repoRoot, cfg.tsconfig), "utf8");
    // The paths key contributes the opener; any closer at all is the hazard.
    const opener = "/" + "*";
    const closer = "*" + "/";
    const afterFirstOpener = raw.slice(raw.indexOf(opener) + 2);
    expect(
      afterFirstOpener.includes(closer),
      `${cfg.tsconfig} contains a block-comment closer after the "@/" wildcard key; ` +
        "the converter will strip its paths object and silently disable alias resolution",
    ).toBe(false);
  });
});
