import "@testing-library/jest-dom/vitest";
import { act, cleanup, waitFor } from "@testing-library/react";
import { configure } from "@testing-library/react";
import { toast } from "sonner";
import { afterEach, beforeAll, afterAll, vi } from "vitest";
import { server } from "./mocks/server";

// Increase default waitFor timeout to handle parallel test load. The suite is
// large (~3.7k tests) and page tests do async data loading; under full-parallel
// CPU saturation a 5s waitFor can still flake even though every test passes in
// isolation, so give it more headroom (passing assertions still resolve instantly).
configure({ asyncUtilTimeout: 10_000 });

// Mock keycloak-js globally so auth-provider doesn't try to connect
vi.mock("keycloak-js", () => ({
  default: class MockKeycloak {
    authenticated = false;
    token = "";
    tokenParsed = {};
    onTokenExpired: (() => void) | null = null;
    init() {
      return Promise.resolve(false);
    }
    login() {
      return Promise.resolve();
    }
    logout() {
      return Promise.resolve();
    }
    updateToken() {
      return Promise.resolve(false);
    }
    loadUserProfile() {
      return Promise.resolve({});
    }
  },
}));

// Neutralise the Monaco bootstrap globally.
//
// The four editor components import `@/lib/monaco-setup` for its side effect, so
// that `loader.config({ monaco })` has run before <Editor> mounts and Monaco is
// never fetched from the jsDelivr CDN. In tests that side effect is both
// unnecessary and impossible: `@monaco-editor/react` is mocked, and the real
// `monaco-editor` package ships raw `.css` and `?worker` imports that Node
// cannot load — which is exactly why `vitest.config.ts` externalises it.
//
// A factory mock is what keeps the real module from being evaluated at all; an
// automock would still resolve and import it to introspect its shape.
vi.mock("@/lib/monaco-setup", () => ({}));

// Mock window.matchMedia for theme-provider (JSDOM doesn't implement it)
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: query === "(prefers-color-scheme: dark)",
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// jsdom doesn't implement URL.revokeObjectURL; provide a no-op so attachment
// preview cleanup (revoke on unmount / message clear) never throws in tests.
if (typeof URL.revokeObjectURL !== "function") {
  URL.revokeObjectURL = () => {};
}

import "@/i18n/config";

// Start MSW server before all tests
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));

/**
 * Let sonner finish removing its toasts while the test's DOM still exists.
 *
 * A dismissed toast — dismissed explicitly, or when its `duration` runs out —
 * is removed by a bare `setTimeout(removeToast, 200)` (sonner's
 * TIME_BEFORE_UNMOUNT) that nothing cancels on unmount. `cleanup()` below
 * unmounts the Toaster first and that timer fires afterwards; in a file's LAST
 * test it can fire after vitest has torn the jsdom environment down, and the
 * state update it makes then dies in React on `window is not defined`. Vitest
 * reports that as an unhandled error and fails the run with every test green —
 * which is what happened, once, in CI (run 36418007466). It does not reproduce
 * on demand: it needs a runner slow enough to lose the race. A probe counting
 * those timers is deterministic, though — before this hook,
 * resource-detail-save-not-live.test.tsx ended every run with one still pending.
 *
 * So dismiss whatever is on screen and wait for sonner to take it out of the
 * DOM, which is exactly the moment its removal timer has fired. A file that
 * never renders a Toaster finds nothing and pays nothing. This also clears
 * sonner's module-global store between tests, so one test's toast cannot turn
 * up in the next.
 */
async function drainToasts() {
  if (!document.querySelector("[data-sonner-toast]")) {
    return;
  }
  act(() => {
    toast.dismiss();
  });
  if (vi.isFakeTimers()) {
    // Under fake timers RTL's waitFor polls with the faked clock and would never
    // advance; move it past the removal delay instead.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
  }
  await waitFor(
    () => {
      if (document.querySelector("[data-sonner-toast]")) {
        throw new Error("a sonner toast is still in the DOM after toast.dismiss()");
      }
    },
    { timeout: 3_000 },
  );
}

// Reset handlers after each test
afterEach(async () => {
  await drainToasts();
  cleanup();
  localStorage.clear();
  server.resetHandlers();
});

// Clean up after all tests
afterAll(() => server.close());
