// Produces a scoped, stable design-system stylesheet for /design-sync.
//
// EDDI-Manager has no library dist; its app CSS (dist/assets/index-<hash>.css)
// is hash-named (not reproducible) and pulls in Monaco/VSCode CSS the synced
// components never use. This regenerates src/index.css's tokens + ONLY the
// Tailwind utilities used by src/components/ui + src/components/shared + the
// authored previews, to a fixed path: .design-sync/.cache/compiled.css.
//
// Wired as cfg.buildCmd so re-sync regenerates it before the converter runs.
// Requires @tailwindcss/cli in .ds-sync/node_modules (staged converter deps).
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url)); // .design-sync
const repoRoot = resolve(here, "..");
const cacheDir = resolve(here, ".cache");
mkdirSync(cacheDir, { recursive: true });

// Swap the project-wide `@import "tailwindcss"` for a scoped one: source(none)
// disables auto content-scan, and explicit @source dirs limit utility
// generation to the synced surface (no Monaco/editor classes).
let css = readFileSync(resolve(repoRoot, "src/index.css"), "utf8");
const scoped = [
  '@import "tailwindcss" source(none);',
  '@source "../../src/components/ui";',
  '@source "../../src/components/shared";',
  '@source "../ds-entry.tsx";',
  '@source "../previews";',
].join("\n");
if (!/@import\s+['"]tailwindcss['"]\s*;/.test(css)) {
  console.error("build-css: src/index.css has no `@import 'tailwindcss';` — aborting");
  process.exit(1);
}
css = css.replace(/@import\s+['"]tailwindcss['"]\s*;/, scoped);

const inputPath = resolve(cacheDir, "ds-tailwind-input.css");
writeFileSync(inputPath, css);

const cli = resolve(repoRoot, ".ds-sync/node_modules/@tailwindcss/cli/dist/index.mjs");
if (!existsSync(cli)) {
  console.error("build-css: @tailwindcss/cli not found in .ds-sync — run `npm i @tailwindcss/cli` there");
  process.exit(1);
}
const outPath = resolve(cacheDir, "compiled.css");
execFileSync(process.execPath, [cli, "-i", inputPath, "-o", outPath, "--minify"], {
  stdio: "inherit",
  cwd: repoRoot,
});
console.log("build-css: wrote " + outPath);
