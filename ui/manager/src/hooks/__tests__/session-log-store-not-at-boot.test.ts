import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * The app entry point must not import `session-log-store` for its side effect.
 *
 * It did, with the comment "Start collecting logs from session start", and the
 * module connected on load — so every Manager tab held an open
 * `/administration/logs/stream` SSE connection on **every page**, for the whole
 * lifetime of the tab, whether or not anyone ever opened the Logs page. EDDI
 * serves HTTP/1.1, where Chrome allows six concurrent connections per origin
 * across the entire browser profile, and a live group discussion opens another
 * one. Two or three tabs saturated the cap: unrelated pages hung on skeleton
 * loaders forever, intermittently, while the server answered every request in
 * 0.21 s with zero variance. It looks exactly like a dead backend.
 *
 * The runtime behaviour is covered by `session-log-store.test.ts` and
 * `use-logs.test.tsx`. Neither can see this: re-adding a bare import to
 * `main.tsx` would put the connection back at boot without failing either, and
 * `main.tsx` is not importable from a test (it calls `createRoot`). So this
 * reads the source.
 */
describe("main.tsx", () => {
  // Resolved from the vitest root (the package directory), not from
  // `import.meta.url` — vitest serves test modules over an http:// URL, so
  // `fileURLToPath` on it throws "The URL must be of scheme file".
  const source = readFileSync(resolve(process.cwd(), "src/main.tsx"), "utf-8");

  it("does not import the session log store", () => {
    // Match the import, not the word: the file explains the omission in a
    // comment, and that comment naturally names the module.
    const importsTheStore = /^\s*import\s[^\n]*session-log-store/m.test(source);

    expect(importsTheStore).toBe(false);
  });
});
