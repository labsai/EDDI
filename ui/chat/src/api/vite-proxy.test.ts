/* The dev server proxies the backend by path prefix. A prefix the API layer
   calls but the proxy does not list is answered by Vite with the SPA's
   index.html — attachment upload failed that way under `npm run dev` only. */
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { describe, it, expect } from "vitest";

const ROOT = process.cwd();

function apiPrefixes(): Set<string> {
  const dir = resolve(ROOT, "src/api");
  const prefixes = new Set<string>();
  for (const file of readdirSync(dir)) {
    // demo-api.ts only shows sample code as text; it calls nothing.
    if (!file.endsWith(".ts") || file.endsWith(".test.ts") || file === "demo-api.ts") continue;
    // Comment lines name routes too (`/chat/{environment}/…`); only code counts.
    const source = readFileSync(resolve(dir, file), "utf8")
      .split("\n")
      .filter((line) => !/^\s*(\*|\/\/|\/\*)/.test(line))
      .join("\n");
    for (const match of source.matchAll(/`\/([a-z][a-z0-9-]*)\//gi)) {
      prefixes.add(`/${match[1]}`);
    }
  }
  return prefixes;
}

function proxiedPrefixes(): Set<string> {
  const config = readFileSync(resolve(ROOT, "vite.config.ts"), "utf8");
  const proxy = config.slice(config.indexOf("proxy:"));
  return new Set([...proxy.matchAll(/"(\/[a-z0-9-]+)":\s*\{/gi)].map((m) => m[1]));
}

describe("vite dev proxy", () => {
  it("finds the API's path prefixes (guards the scan itself)", () => {
    const prefixes = apiPrefixes();
    expect(prefixes).toContain("/agents");
    expect(prefixes).toContain("/conversations");
  });

  it("proxies every path prefix the API layer calls", () => {
    const proxied = proxiedPrefixes();
    const missing = [...apiPrefixes()].filter((p) => !proxied.has(p));
    expect(missing).toEqual([]);
  });
});
