import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { Suspense } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { lazyPage } from "../lazy-page";

function Ok() {
  return <div data-testid="loaded">loaded</div>;
}

const RELOAD_GUARD_KEY = "eddi-chunk-reload";

describe("lazyPage", () => {
  let reload: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    sessionStorage.clear();
    reload = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...originalLocation, reload },
    });
  });

  afterEach(() => {
    Object.defineProperty(window, "location", {
      configurable: true,
      value: originalLocation,
    });
    vi.restoreAllMocks();
  });

  it("resolves the NAMED export, not a default one", async () => {
    const Lazy = lazyPage(async () => ({ Ok }), "Ok");
    render(
      <Suspense fallback={<span>loading</span>}>
        <Lazy />
      </Suspense>,
    );
    expect(await screen.findByTestId("loaded")).toBeInTheDocument();
  });

  it("clears the reload guard once a chunk loads cleanly", async () => {
    sessionStorage.setItem(RELOAD_GUARD_KEY, "1");
    const Lazy = lazyPage(async () => ({ Ok }), "Ok");
    render(
      <Suspense fallback={<span>loading</span>}>
        <Lazy />
      </Suspense>,
    );
    await screen.findByTestId("loaded");
    // A later deploy must get its own retry rather than inheriting this one.
    expect(sessionStorage.getItem(RELOAD_GUARD_KEY)).toBeNull();
  });

  it.each([
    "Failed to fetch dynamically imported module: /assets/dashboard-OLD.js",
    "error loading dynamically imported module",
    "Importing a module script failed.",
  ])("reloads once on a stale-chunk 404: %s", async (message) => {
    const Lazy = lazyPage(() => Promise.reject(new Error(message)), "Ok");
    render(
      <Suspense fallback={<span>loading</span>}>
        <Lazy />
      </Suspense>,
    );
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
    expect(sessionStorage.getItem(RELOAD_GUARD_KEY)).toBe("1");
  });

  it("does NOT reload twice — a broken deploy must not loop the tab", async () => {
    sessionStorage.setItem(RELOAD_GUARD_KEY, "1");
    const error = new Error("Failed to fetch dynamically imported module: /assets/x.js");
    const Lazy = lazyPage(() => Promise.reject(error), "Ok");

    // The rejection now propagates instead of reloading. React logs the boundary
    // miss; silence it so the failure mode under test is the only signal.
    vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <Suspense fallback={<span>loading</span>}>
        <Lazy />
      </Suspense>,
    );
    await waitFor(() => expect(sessionStorage.getItem(RELOAD_GUARD_KEY)).toBe("1"));
    expect(reload).not.toHaveBeenCalled();
  });

  it("does NOT reload on an ordinary error inside the chunk", async () => {
    // A genuine bug in the page module must reach the ErrorBoundary, not get
    // papered over by a refresh that will fail identically.
    vi.spyOn(console, "error").mockImplementation(() => {});
    const Lazy = lazyPage(() => Promise.reject(new TypeError("x is not a function")), "Ok");
    render(
      <Suspense fallback={<span>loading</span>}>
        <Lazy />
      </Suspense>,
    );
    await new Promise((r) => setTimeout(r, 50));
    expect(reload).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(RELOAD_GUARD_KEY)).toBeNull();
  });
});
