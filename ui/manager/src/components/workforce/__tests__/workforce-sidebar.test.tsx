import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkforceSidebar } from "@/components/workforce/workforce-sidebar";

/**
 * The Workforce navigation shell, previously at 0% coverage — which is why both
 * reported bugs lived here: the only route back to the dashboard was an
 * unlabelled logo, and no test noticed.
 */

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => mockNavigate };
});

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

function mockBoards(boards: Array<{ id: string; name: string }>) {
  server.use(
    http.get("*/groupstore/groups/descriptors", () =>
      HttpResponse.json(
        boards.map((b) => ({
          resource: `eddi://ai.labs.group/groupstore/groups/${b.id}?version=1`,
          name: b.name,
          description: "",
          createdOn: 0,
          lastModifiedOn: 0,
        })),
      ),
    ),
    http.get("*/groupstore/groups/:id", ({ params }) =>
      HttpResponse.json({
        name: boards.find((b) => b.id === String(params.id))?.name ?? "Group",
        description: "",
        style: "ROUND_TABLE",
        members: [{ agentId: "a1", displayName: "Ana" }],
      }),
    ),
  );
}

function render(path = "/workforce", pattern = "/workforce", collapsed = false) {
  return renderPage(
    path,
    <WorkforceSidebar collapsed={collapsed} onToggle={() => {}} />,
    pattern,
  );
}

beforeEach(() => {
  mockNavigate.mockClear();
  server.resetHandlers();
});

describe("WorkforceSidebar", () => {
  it("offers a labelled route back to the dashboard", async () => {
    // The reported bug: from Insights there was no way back, because the only
    // link home was the unlabelled logo.
    mockBoards([]);
    render();

    const dashboard = await screen.findByText(/dashboard/i);
    expect(dashboard.closest("a")).toHaveAttribute("href", "/workforce");
  });

  it("links to Insights and to the wizard", async () => {
    mockBoards([]);
    render();

    expect((await screen.findByText(/insights/i)).closest("a")).toHaveAttribute(
      "href",
      "/workforce/analytics",
    );
    expect(screen.getByText(/assemble task force/i).closest("a")).toHaveAttribute(
      "href",
      "/workforce/new",
    );
  });

  it("lists task forces from the backend", async () => {
    mockBoards([
      { id: "b1", name: "Launch review" },
      { id: "b2", name: "Risk board" },
    ]);
    render();

    expect(await screen.findByText("Launch review")).toBeInTheDocument();
    expect(screen.getByText("Risk board")).toBeInTheDocument();
  });

  it("marks the active nav item with the current route", async () => {
    mockBoards([]);
    render("/workforce/analytics", "/workforce/analytics");

    // Standalone `bg-muted`, not the `hover:bg-muted` every inactive link
    // carries — matching loosely would pass for both and assert nothing.
    const isActive = (el: Element) => /(^|\s)bg-muted(\s|$)/.test(el.className);

    const insights = (await screen.findByText(/insights/i)).closest("a")!;
    // Active styling is how the user knows where they are; both links exist on
    // every page, so only the class distinguishes them.
    expect(isActive(insights)).toBe(true);

    const dashboard = screen.getByText(/dashboard/i).closest("a")!;
    expect(isActive(dashboard)).toBe(false);
  });

  it("hides labels but keeps the links when collapsed", async () => {
    mockBoards([]);
    render("/workforce", "/workforce", true);

    await waitFor(() =>
      expect(
        document.querySelector('a[href="/workforce/analytics"]'),
      ).toBeInTheDocument(),
    );
    // Collapsed rail shows icons only, so the visible text is gone while the
    // navigation target must remain reachable.
    expect(screen.queryByText(/assemble task force/i)).toBeNull();
    expect(document.querySelector('a[href="/workforce/new"]')).toBeInTheDocument();
  });

  it("renders a close button instead of the collapse toggle when onClose is given", async () => {
    // The tablet drawer passes onClose; the desktop rail passes only onToggle.
    mockBoards([]);
    const onClose = vi.fn();
    renderPage(
      "/workforce",
      <WorkforceSidebar collapsed={false} onToggle={() => {}} onClose={onClose} />,
      "/workforce",
    );

    fireEvent.click(await screen.findByRole("button", { name: /close sidebar/i }));
    expect(onClose).toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: /toggle sidebar/i })).toBeNull();
  });

  it("calls onToggle from the collapse control", async () => {
    mockBoards([]);
    const onToggle = vi.fn();
    renderPage(
      "/workforce",
      <WorkforceSidebar collapsed={false} onToggle={onToggle} />,
      "/workforce",
    );

    fireEvent.click(await screen.findByRole("button", { name: /toggle sidebar/i }));
    expect(onToggle).toHaveBeenCalled();
  });

  it("still renders its navigation when the board list fails to load", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    render();

    // Losing the board list must not take the whole shell down with it.
    expect(await screen.findByText(/dashboard/i)).toBeInTheDocument();
    expect(screen.getByText(/insights/i)).toBeInTheDocument();
  });
});
