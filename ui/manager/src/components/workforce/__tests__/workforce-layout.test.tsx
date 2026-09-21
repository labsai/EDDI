import { describe, it, expect, beforeEach } from "vitest";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceLayout } from "@/components/workforce/workforce-layout";

/**
 * The Workforce shell picks one of three chromes by viewport, and previously had
 * 0% coverage. It decides whether the sidebar is visible at all — the tablet
 * drawer is why "no way back from Insights" was worse than it first looked.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

/** jsdom reports 1024 by default; set the width the layout reads on mount. */
function setViewport(width: number) {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: width,
  });
}

function render(path = "/workforce") {
  return renderPage(path, <WorkforceLayout />, "/workforce");
}

beforeEach(() => {
  server.resetHandlers();
  localStorage.clear();
  server.use(
    http.get("*/groupstore/groups/descriptors", () => HttpResponse.json([])),
  );
  setViewport(1280);
});

describe("WorkforceLayout", () => {
  it("renders the sidebar inline on desktop", async () => {
    setViewport(1280);
    render();

    expect(await screen.findByText(/dashboard/i)).toBeInTheDocument();
    // No drawer, so no modal dialog wrapper.
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("uses bottom tabs and hides the sidebar on mobile", async () => {
    setViewport(400);
    render();

    await waitFor(() =>
      expect(screen.getByRole("navigation", { name: /bottom navigation/i })).toBeInTheDocument(),
    );
    // The sidebar's Assemble button is absent; navigation is the tab bar.
    expect(screen.queryByText(/assemble task force/i)).toBeNull();
  });

  it("keeps the sidebar behind a drawer on tablet until the menu is opened", async () => {
    setViewport(800);
    render();

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /menu/i })).toBeInTheDocument(),
    );
    // This is the case that stranded users: the sidebar exists but is not on
    // screen, so a page without its own back link leaves no way out.
    expect(screen.queryByText(/assemble task force/i)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /menu/i }));

    const drawer = await screen.findByRole("dialog");
    expect(drawer).toHaveAttribute("aria-modal", "true");
    expect(await screen.findByText(/assemble task force/i)).toBeInTheDocument();
  });

  it("closes the tablet drawer on Escape", async () => {
    setViewport(800);
    render();

    fireEvent.click(await screen.findByRole("button", { name: /menu/i }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("closes the tablet drawer when the backdrop is clicked", async () => {
    setViewport(800);
    render();

    fireEvent.click(await screen.findByRole("button", { name: /menu/i }));
    await screen.findByRole("dialog");

    // Target the backdrop specifically: `[aria-hidden="true"]` alone also
    // matches every decorative icon in the chrome, so a loose selector clicks
    // the wrong element and the drawer never closes.
    const backdrop = document.querySelector('div.fixed.inset-0[aria-hidden="true"]');
    expect(backdrop).not.toBeNull();
    fireEvent.click(backdrop!);

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("switches chrome when the window is resized across a breakpoint", async () => {
    setViewport(1280);
    render();
    expect(await screen.findByText(/dashboard/i)).toBeInTheDocument();

    setViewport(400);
    fireEvent(window, new Event("resize"));

    await waitFor(() =>
      expect(screen.getByRole("navigation", { name: /bottom navigation/i })).toBeInTheDocument(),
    );
  });

  it("offers a skip-to-content link on every viewport", async () => {
    for (const width of [400, 800, 1280]) {
      setViewport(width);
      const { unmount } = render();
      expect(await screen.findByText(/skip to content/i)).toBeInTheDocument();
      unmount();
    }
  });

  it("persists the desktop collapse state across mounts", async () => {
    setViewport(1280);
    const { unmount } = render();

    fireEvent.click(await screen.findByRole("button", { name: /toggle sidebar/i }));
    await waitFor(() =>
      expect(localStorage.getItem("workforce-sidebar-collapsed")).toBe("true"),
    );
    unmount();

    render();
    // Collapsed rail: the link survives, its label does not.
    await waitFor(() =>
      expect(document.querySelector('a[href="/workforce/new"]')).toBeInTheDocument(),
    );
    expect(screen.queryByText(/assemble task force/i)).toBeNull();
  });
});
