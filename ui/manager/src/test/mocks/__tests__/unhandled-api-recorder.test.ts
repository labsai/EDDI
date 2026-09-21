import { describe, expect, it, beforeEach, vi } from "vitest";
import { recordUnhandledApiRequest } from "@/test/mocks/unhandled-api-recorder";
import { UNHANDLED_API_REQUESTS_KEY } from "@/test/mocks/unhandled-api";

/**
 * The recorder behind the E2E tier's "no unhandled API calls" guard.
 *
 * It is worth testing precisely because it is a guard: a guard that quietly
 * records the wrong things, or nothing at all, reports green either way. The
 * first version of this function filtered on `request.destination !== ""`,
 * reasoning that browser-initiated subresources name a type while fetch/XHR
 * report `""` — true of a real Request, but MSW's worker serialises the request
 * and the client rebuilds it with `new Request(url, {...serialized})`, and
 * `destination` is not a `RequestInit` member. Every reconstructed Request
 * reports `""`, so the check could never fire and `/eddi-icon.svg` would have
 * failed any test that reloads the page. These cases pin the replacement.
 */

const ORIGIN = "http://localhost:3000";

/** Matches the two-method shape MSW passes as the second callback argument. */
function printer() {
  return { warning: vi.fn(), error: vi.fn() };
}

function record(url: string, method = "GET") {
  const print = printer();
  recordUnhandledApiRequest(new Request(url, { method }), print);
  return print;
}

function recorded(): string[] {
  const raw = sessionStorage.getItem(UNHANDLED_API_REQUESTS_KEY);
  return raw ? (JSON.parse(raw) as string[]) : [];
}

describe("recordUnhandledApiRequest", () => {
  beforeEach(() => {
    sessionStorage.clear();
    delete (window as unknown as Record<string, unknown>)[UNHANDLED_API_REQUESTS_KEY];
  });

  it("records a same-origin API call that no handler answered", () => {
    const print = record(`${ORIGIN}/agentstore/agents/descriptors?limit=1`);
    expect(recorded()).toEqual(["GET /agentstore/agents/descriptors?limit=1"]);
    expect(print.warning).toHaveBeenCalled();
  });

  it("keeps the method, so a missing POST is not mistaken for a missing GET", () => {
    record(`${ORIGIN}/agentstore/agents`, "POST");
    expect(recorded()).toEqual(["POST /agentstore/agents"]);
  });

  it("ignores static assets the app really does serve", () => {
    // The regression that motivated this file: index.html links this icon, and
    // MSW's own asset filter is bypassed when onUnhandledRequest is a function.
    record(`${ORIGIN}/eddi-icon.svg`);
    record(`${ORIGIN}/eddi-icon.ico`);
    record(`${ORIGIN}/assets/index-a1b2c3.js`);
    record(`${ORIGIN}/assets/index-a1b2c3.css`);
    record(`${ORIGIN}/fonts/noto-sans.woff2`);
    expect(recorded()).toEqual([]);
  });

  it("ignores the dev server's own module requests", () => {
    record(`${ORIGIN}/src/main.tsx`);
    record(`${ORIGIN}/@vite/client`);
    record(`${ORIGIN}/node_modules/.vite/deps/react.js`);
    expect(recorded()).toEqual([]);
  });

  it("ignores off-origin requests", () => {
    record("https://api.github.com/repos/labsai/EDDI/releases/latest");
    expect(recorded()).toEqual([]);
  });

  it("deduplicates a polled endpoint instead of repeating it", () => {
    // use-coordinator refetches every 5s; without this one missing handler
    // would crowd the failure message and creep towards the storage quota.
    record(`${ORIGIN}/coordinator/status`);
    record(`${ORIGIN}/coordinator/status`);
    record(`${ORIGIN}/coordinator/status`);
    expect(recorded()).toEqual(["GET /coordinator/status"]);
  });

  it("still warns through MSW even when the record is a duplicate", () => {
    record(`${ORIGIN}/coordinator/status`);
    const second = record(`${ORIGIN}/coordinator/status`);
    expect(second.warning).toHaveBeenCalled();
  });

  it("falls back to the document when sessionStorage is unavailable", () => {
    const setItem = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new Error("quota exceeded");
      });
    try {
      record(`${ORIGIN}/quotas`);
      const fallback = (window as unknown as Record<string, string[] | undefined>)[
        UNHANDLED_API_REQUESTS_KEY
      ];
      expect(fallback).toEqual(["GET /quotas"]);
    } finally {
      setItem.mockRestore();
    }
  });
});
