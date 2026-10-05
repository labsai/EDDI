import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AppLayout } from "../app-layout";

function Crash(): never {
  throw new Error("page exploded");
}

function renderLayout(initialRoute = "/manage") {
  return renderWithProviders(
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/manage" element={<p>dashboard body</p>} />
        <Route path="/manage/agents" element={<p>agents body</p>} />
        <Route path="/manage/boom" element={<Crash />} />
      </Route>
    </Routes>,
    { initialRoute },
  );
}

function setWidth(width: number) {
  window.innerWidth = width;
  act(() => {
    window.dispatchEvent(new Event("resize"));
  });
}

describe("AppLayout — mobile navigation drawer", () => {
  const originalWidth = window.innerWidth;

  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    window.innerWidth = originalWidth;
  });

  it("is a modal dialog, takes focus when opened and gives it back on Escape", async () => {
    setWidth(500);
    const user = userEvent.setup();
    renderLayout();

    const menuBtn = screen.getByTestId("mobile-menu-toggle");
    menuBtn.focus();
    await user.click(menuBtn);

    const drawer = await screen.findByRole("dialog", { name: /main navigation/i });
    expect(drawer).toHaveAttribute("aria-modal", "true");
    await waitFor(() => expect(drawer.contains(document.activeElement)).toBe(true));

    await user.keyboard("{Escape}");
    expect(screen.queryByTestId("mobile-nav-drawer")).not.toBeInTheDocument();
    await waitFor(() => expect(menuBtn).toHaveFocus());
  });

  it("closes once a navigation lands", async () => {
    setWidth(500);
    const user = userEvent.setup();
    renderLayout();

    await user.click(screen.getByTestId("mobile-menu-toggle"));
    const drawer = await screen.findByTestId("mobile-nav-drawer");
    await user.click(within(drawer).getByRole("link", { name: /^agents$/i }));

    await waitFor(() => expect(screen.queryByTestId("mobile-nav-drawer")).not.toBeInTheDocument());
    expect(await screen.findByText("agents body")).toBeInTheDocument();
  });

  it("offers the language selector, which the top bar hides on phones", async () => {
    setWidth(500);
    const user = userEvent.setup();
    renderLayout();

    await user.click(screen.getByTestId("mobile-menu-toggle"));
    const drawer = await screen.findByTestId("mobile-nav-drawer");
    expect(within(drawer).getByRole("combobox", { name: "Language" })).toBeInTheDocument();
  });

  it("starts in the mobile layout on a phone — no desktop sidebar flash", () => {
    window.innerWidth = 500;
    renderLayout();
    // Present on the very first render, before any effect or resize event.
    expect(screen.queryByTestId("sidebar")).not.toBeInTheDocument();
    expect(screen.getByTestId("mobile-menu-toggle")).toBeInTheDocument();
  });
});

describe("AppLayout — sidebar collapse", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    localStorage.clear();
    window.innerWidth = 1280;
  });

  it("remembers a collapsed sidebar across mounts", async () => {
    const user = userEvent.setup();
    const first = renderLayout();
    await user.click(screen.getByTestId("sidebar-toggle"));
    expect(screen.getByTestId("sidebar").className).toContain("w-16");
    first.unmount();

    renderLayout();
    expect(screen.getByTestId("sidebar").className).toContain("w-16");
  });
});

describe("AppLayout — a crashing page", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    window.innerWidth = 1280;
  });

  it("keeps the shell and recovers when the user navigates away", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    try {
      const user = userEvent.setup();
      renderLayout("/manage/boom");

      expect(await screen.findByTestId("error-boundary-fallback")).toBeInTheDocument();
      // The sidebar and top bar survived — the old single app-level boundary
      // replaced the whole tree.
      const sidebar = screen.getByTestId("sidebar");
      expect(sidebar).toBeInTheDocument();

      await user.click(within(sidebar).getByRole("link", { name: /^agents$/i }));
      expect(await screen.findByText("agents body")).toBeInTheDocument();
      expect(screen.queryByTestId("error-boundary-fallback")).not.toBeInTheDocument();
      expect(screen.getByTestId("sidebar")).toBe(sidebar);
    } finally {
      spy.mockRestore();
    }
  });
});
