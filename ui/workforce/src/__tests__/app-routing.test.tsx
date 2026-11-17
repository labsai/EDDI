import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { App } from "@/app";

/**
 * Route-level smoke tests for Workforce's code-split router: a lazy page wired
 * to the wrong export is a chunk that rejects at navigation time, and only
 * rendering the route catches it. Each case asserts the page ARRIVED, not
 * merely that the skeleton showed.
 */
describe("Workforce routing (code-split)", () => {
  it("resolves the dashboard inside the Workforce shell", async () => {
    renderWithProviders(<App />, { initialRoute: "/workforce" });
    await waitFor(() => expect(screen.getByTestId("workforce-layout")).toBeInTheDocument(), { timeout: 15_000 });
    await waitFor(() => expect(document.querySelector("[data-testid='page-loader']")).toBeNull(), {
      timeout: 15_000,
    });
  });

  it("says so for an unknown /workforce path, linking back to Workforce", async () => {
    renderWithProviders(<App />, { initialRoute: "/workforce/board-1/not-a-page/extra" });
    const home = await screen.findByTestId("not-found-home", undefined, { timeout: 15_000 });
    expect(home).toHaveAttribute("href", "/workforce");
  });

  it("redirects the legacy capital-W /Workforce path, sub-path intact", async () => {
    renderWithProviders(<App />, { initialRoute: "/Workforce/some-board" });
    await waitFor(() => expect(document.querySelector("#workforce-main")).not.toBeNull(), { timeout: 15_000 });
  });

  it("sends a path outside /workforce home (only the dev server serves one)", async () => {
    renderWithProviders(<App />, { initialRoute: "/somewhere-else" });
    await waitFor(() => expect(screen.getByTestId("workforce-layout")).toBeInTheDocument(), { timeout: 15_000 });
  });
});
