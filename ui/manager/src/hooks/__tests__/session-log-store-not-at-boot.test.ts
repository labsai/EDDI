import { describe, it, expect, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * Nothing may open the log SSE stream at application boot — not the entry
 * point, and not the store itself.
 *
 * `main.tsx` used to import `session-log-store` for its side effect, with the
 * comment "Start collecting logs from session start", and the module connected
 * on load. So every Manager tab held an open `/administration/logs/stream` SSE
 * connection on **every page**, for the whole lifetime of the tab, whether or
 * not anyone ever opened the Logs page. EDDI serves HTTP/1.1, where Chrome
 * allows six concurrent connections per origin across the entire browser
 * profile, and a live group discussion opens another one. Two or three tabs
 * saturated the cap: unrelated pages hung on skeleton loaders forever,
 * intermittently, while the server answered every request in 0.21 s with zero
 * variance. It looks exactly like a dead backend.
 *
 * Two halves, because a regression could come back through either.
 */
describe("main.tsx", () => {
  /*
   * Resolved from the vitest root (the package directory), not from
   * `import.meta.url` — vitest serves test modules over an http:// URL, so
   * `fileURLToPath` on it throws "The URL must be of scheme file".
   *
   * Read as source because `main.tsx` is not importable from a test (it calls
   * `createRoot`), and because the runtime half below cannot see a bare import
   * in a file it never loads.
   */
  const source = readFileSync(resolve(process.cwd(), "src/main.tsx"), "utf-8");

  it("does not import the session log store", () => {
    // Match the import, not the word: the file explains the omission in a
    // comment, and that comment naturally names the module.
    const importsTheStore = /^\s*import\s[^\n]*session-log-store/m.test(source);

    expect(importsTheStore).toBe(false);
  });
});

/**
 * The store must not connect on load by ANY route, including a deferred one.
 *
 * Two things had to be arranged before this assertion could fail, and both are
 * why the original regression was invisible to a test suite that otherwise
 * covered this module well:
 *
 *  1. **The removed code was `setTimeout(connect, 2000)`**, not a synchronous
 *     call. Sampling the connection state at import time cannot catch it —
 *     nothing is open yet when the module finishes evaluating. Hence the fake
 *     timers.
 *  2. **jsdom does not implement `EventSource`.** The removed auto-connect was
 *     guarded by `typeof EventSource !== "undefined"`, which is false in this
 *     environment, so the boot connection never fired under test no matter how
 *     the test was written. Stubbing the global is what puts the module on the
 *     browser branch it actually shipped on.
 *
 * Without (2) this test passes with the regression reintroduced verbatim —
 * verified by doing exactly that.
 */
describe("session-log-store", () => {
  it("opens no EventSource on import, even on a timer, on the browser branch", async () => {
    // A stand-in for the global the module's browser guard tests for. Never
    // constructed: the point is that nothing gets as far as constructing one.
    vi.stubGlobal("EventSource", class {});
    vi.useFakeTimers();
    try {
      vi.resetModules();
      const logs = await import("@/lib/api/logs");
      const spy = vi
        .spyOn(logs, "createLogEventSource")
        .mockReturnValue({ addEventListener() {}, close() {} } as never);

      const store = await import("@/hooks/session-log-store");

      // Anything the module scheduled for later now runs.
      await vi.runAllTimersAsync();

      expect(spy).not.toHaveBeenCalled();
      expect(store.isStreamOpen()).toBe(false);
      expect(store.subscriberCount()).toBe(0);
      spy.mockRestore();
    } finally {
      vi.useRealTimers();
      vi.unstubAllGlobals();
      // Leave no half-initialised copy of the store behind for the next file.
      vi.resetModules();
    }
  });
});
