import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders, renderPage } from "@/test/test-utils";

import { QuickActions } from "../quick-actions";
import { WorkforceBottomTabs } from "../workforce-bottom-tabs";

/**
 * Regressions for reported navigation bugs in the Workforce app.
 *
 * The pre-existing tests here asserted only that *some* link contained
 * "/workforce" and that three tab buttons rendered, which is why a tile that
 * linked to the page it sat on and a tab that matched no route both shipped.
 */

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>(
    "react-router-dom",
  );
  return { ...actual, useNavigate: () => mockNavigate };
});

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

beforeEach(() => {
  mockNavigate.mockClear();
});

describe("QuickActions tiles", () => {
  /** label fragment -> expected href */
  const EXPECTED: Array<[RegExp, string]> = [
    [/assemble task force/i, "/workforce/new"],
    [/chat with agent/i, "/workforce/chat"],
    [/view insights/i, "/workforce/analytics"],
    [/manage workforce/i, "/manage/agents"],
  ];

  for (const [label, href] of EXPECTED) {
    it(`"${label.source}" links to ${href}`, () => {
      renderWithProviders(<QuickActions />, { initialRoute: "/workforce" });
      const link = screen.getByText(label).closest("a");
      expect(link).not.toBeNull();
      expect(link).toHaveAttribute("href", href);
    });
  }

  it("no tile links to the dashboard it is rendered on", () => {
    // "Manage Workforce" pointed at "/workforce" — the page hosting the tile —
    // so clicking it navigated to the current route and appeared to do nothing.
    const { container } = renderWithProviders(<QuickActions />, {
      initialRoute: "/workforce",
    });
    const hrefs = [...container.querySelectorAll("a")].map((a) =>
      a.getAttribute("href"),
    );
    expect(hrefs).not.toContain("/workforce");
    expect(hrefs).toHaveLength(4);
  });
});

describe("WorkforceBottomTabs", () => {
  it("Threads targets the board from a board sub-page", () => {
    renderPage(
      "/workforce/board123/history",
      <WorkforceBottomTabs />,
      "/workforce/:boardId/history",
    );
    fireEvent.click(screen.getByText(/threads/i));
    // Never "/workforce/board123/thread/" — that has no :memberId, matches no
    // route, and fell through to the catch-all redirect to /welcome.
    expect(mockNavigate).toHaveBeenCalledWith("/workforce/board123");
  });

  it("Threads carries ?version= through so the board is not pinned to v1", () => {
    renderPage(
      "/workforce/board123/history?version=7",
      <WorkforceBottomTabs />,
      "/workforce/:boardId/history",
    );
    fireEvent.click(screen.getByText(/threads/i));
    expect(mockNavigate).toHaveBeenCalledWith("/workforce/board123?version=7");
  });

  it("Threads is disabled on the board root, where it would be a no-op", () => {
    // Navigating to the location already displayed changes nothing visible and
    // pushes a duplicate history entry — the dead-control problem this branch
    // fixed for the "Manage Workforce" tile.
    renderPage("/workforce/board123", <WorkforceBottomTabs />, "/workforce/:boardId");
    const threads = screen.getByText(/threads/i).closest("button");
    expect(threads).toBeDisabled();
    fireEvent.click(threads!);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("Threads is disabled on the dashboard, where no board is in scope", () => {
    renderPage("/workforce", <WorkforceBottomTabs />, "/workforce");
    const threads = screen.getByText(/threads/i).closest("button");
    expect(threads).toBeDisabled();
    fireEvent.click(threads!);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it.each(["new", "analytics", "chat"])(
    "treats /workforce/%s as a page, not a board id",
    (subpage) => {
      renderPage(
        `/workforce/${subpage}`,
        <WorkforceBottomTabs />,
        `/workforce/${subpage}`,
      );
      expect(screen.getByText(/threads/i).closest("button")).toBeDisabled();
    },
  );

  it("Home and Insights stay enabled and target real routes", () => {
    renderPage("/workforce", <WorkforceBottomTabs />, "/workforce");

    fireEvent.click(screen.getByText(/insights/i));
    expect(mockNavigate).toHaveBeenCalledWith("/workforce/analytics");

    mockNavigate.mockClear();
    fireEvent.click(screen.getByText(/home/i));
    expect(mockNavigate).toHaveBeenCalledWith("/workforce");
  });
});
