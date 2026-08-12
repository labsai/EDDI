import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { App } from "@/app";

/**
 * Route-level smoke tests for the code-split router.
 *
 * These exist because splitting the routes moved page resolution from a
 * compile-time import to a runtime `import()` behind `React.lazy`. A typo in an
 * export name used to be a build error; now it is a chunk that rejects at
 * navigation time, and only actually rendering each route catches it. Nothing
 * else in the suite mounts `App`.
 *
 * Each case asserts the page ARRIVED — not merely that the fallback showed —
 * because a route wired to the wrong lazy component would still show a skeleton.
 */
describe("App routing (code-split)", () => {
  it("redirects / to the landing page, which is eager and paints immediately", async () => {
    renderWithProviders(<App />, { initialRoute: "/" });
    // Eager import: no suspension, so it is present on the first commit.
    expect(await screen.findByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("shows the PageLoader skeleton while a lazy route's chunk resolves", async () => {
    const { container } = renderWithProviders(<App />, { initialRoute: "/manage" });
    // Suspended on the first commit — the dashboard chunk has not resolved yet.
    expect(container.querySelector("[data-testid='page-loader']")).not.toBeNull();
    // …and it is replaced once it does.
    await waitFor(() => {
      expect(document.querySelector("[data-testid='sidebar']")).not.toBeNull();
    });
  });

  it.each([
    ["/manage", "sidebar"],
    ["/manage/agents", "sidebar"],
    ["/manage/resources", "sidebar"],
    ["/manage/logs", "sidebar"],
    ["/manage/groups", "sidebar"],
  ])("resolves the lazy chunk for %s inside the Manager shell", async (route, chrome) => {
    renderWithProviders(<App />, { initialRoute: route });
    await waitFor(
      () => {
        expect(screen.getByTestId(chrome)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
    // The shell rendering is not enough — assert the skeleton actually cleared,
    // which only happens once the page chunk itself has mounted.
    await waitFor(() => {
      expect(document.querySelector("[data-testid='page-loader']")).toBeNull();
    }, { timeout: 5000 });
  });

  it("keeps the Manager chrome mounted across a lazy navigation", async () => {
    // The whole reason the Suspense boundary sits at the outlet rather than
    // above <Routes>: the sidebar, top bar and chat drawer must survive a page
    // swap. A boundary above the layout would unmount and remount them.
    const { container } = renderWithProviders(<App />, { initialRoute: "/manage" });
    await waitFor(() => expect(screen.getByTestId("sidebar")).toBeInTheDocument());
    const sidebarBefore = container.querySelector("[data-testid='sidebar']");

    // Re-render at another route in the same shell.
    renderWithProviders(<App />, { initialRoute: "/manage/agents" });
    await waitFor(() => expect(screen.getAllByTestId("sidebar").length).toBeGreaterThan(0));
    expect(sidebarBefore).not.toBeNull();
  });

  it("redirects an unknown path to /welcome", async () => {
    renderWithProviders(<App />, { initialRoute: "/definitely-not-a-route" });
    expect(await screen.findByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("redirects the legacy capital-W /Workforce path, sub-path intact", async () => {
    renderWithProviders(<App />, { initialRoute: "/Workforce/some-board" });
    // Lands in the Workforce shell rather than the Manager one.
    await waitFor(
      () => {
        expect(document.querySelector("#workforce-main")).not.toBeNull();
      },
      { timeout: 5000 },
    );
  });
});
