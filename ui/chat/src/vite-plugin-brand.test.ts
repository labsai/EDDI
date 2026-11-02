/**
 * The shells' `%BRAND_*%` placeholders are filled in by EDDI at serve time
 * (BrandedShellRenderer), so the production build must leave them alone — and
 * the dev-server plugin must fill them with exactly what an unbranded EDDI
 * would.
 *
 * The build half runs a real `vite build` over the real chat.html, with only its
 * module entry swapped for a one-line stand-in, plus the brand plugin, as
 * vite.config.ts registers it. The Manager has the same test for its shells.
 */
import { mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { build } from "vite";
import { afterAll, describe, expect, it } from "vitest";
import {
  brandDefaults,
  DEFAULT_BRAND_SCRIPT,
  substituteBrandDefaults,
} from "../scripts/vite-plugin-brand.mjs";

const ROOT = process.cwd();
const SHELLS = ["chat.html"];
const PLACEHOLDER = /%BRAND_[A-Z_]+%/g;

// realpath: on Windows tmpdir() can be an 8.3 short name, which Vite then
// relativises against the long one and refuses as an output path.
const work = realpathSync.native(mkdtempSync(join(tmpdir(), "brand-shells-")));
afterAll(() => rmSync(work, { recursive: true, force: true }));

function shell(name: string): string {
  return readFileSync(resolve(ROOT, name), "utf8");
}

async function buildShell(name: string): Promise<string> {
  const html = shell(name).replace(
    '<script type="module" src="/src/main.tsx"></script>',
    '<script type="module" src="./entry.js"></script>',
  );
  writeFileSync(join(work, name), html);
  writeFileSync(join(work, "entry.js"), 'import "./entry.css";\n');
  writeFileSync(join(work, "entry.css"), ":root{--color-primary:#f59e0b}\n");
  const output = await build({
    configFile: false,
    root: work,
    logLevel: "silent",
    plugins: [brandDefaults({ assets: {} })],
    build: { write: false, rolldownOptions: { input: join(work, name) } },
  });
  const outputs = Array.isArray(output) ? output : [output];
  for (const result of outputs) {
    if (!("output" in result)) continue;
    const page = result.output.find((file) => file.fileName === name);
    if (page && page.type === "asset") return String(page.source);
  }
  throw new Error(`vite build produced no ${name}`);
}

describe("brand placeholders", () => {
  it.each(SHELLS)("%s carries the placeholders and the brand script and stylesheet", (name) => {
    const html = shell(name);
    expect(html).toContain("%BRAND_PRODUCT_NAME%");
    expect(html).toContain('<script src="/branding/__brand__.js"></script>');
    expect(html).toContain('<link rel="stylesheet" href="/branding/brand.css" />');
    expect(html).toContain('<link rel="icon" href="/branding/favicon.ico" />');
  });

  it.each(SHELLS)("vite build leaves %s's placeholders intact", async (name) => {
    const built = await buildShell(name);
    expect(built.match(PLACEHOLDER)).toEqual(shell(name).match(PLACEHOLDER));
    expect(built).toContain('<script src="/branding/__brand__.js"></script>');
  });

  it.each(SHELLS)("in the built %s, brand.css comes after the bundle's stylesheet", async (name) => {
    const built = await buildShell(name);
    const bundleCss = built.search(/href="\/assets\/[^"]+\.css"/);
    expect(bundleCss, "the bundle stylesheet link").not.toBe(-1);
    expect(built.indexOf('href="/branding/brand.css"')).toBeGreaterThan(bundleCss);
  });
});

describe("dev-server defaults", () => {
  it("renders chat.html's head exactly as before branding existed", () => {
    const chat = substituteBrandDefaults(shell("chat.html"), "chat.html");
    expect(chat).toContain("<title>EDDI Chat</title>");
    expect(chat).toContain('<meta name="theme-color" content="#1a1a1a" />');
    expect(chat).toContain('<meta name="description" content="EDDI — Open Source Conversational AI" />');
    expect(chat).toContain('<meta property="og:title" content="EDDI Chat" />');
    expect(chat).toContain('<meta property="og:description" content="Open Source Conversational AI" />');
    expect(chat).not.toMatch(PLACEHOLDER);
  });

  it("is a dev-server-only plugin that serves the unbranded script", () => {
    const plugin = brandDefaults({ assets: {} });
    expect(plugin.apply).toBe("serve");
    expect(DEFAULT_BRAND_SCRIPT).toMatch(/^window\.__EDDI_BRAND__=\{branded:false,/);
  });
});
