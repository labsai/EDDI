import { describe, it, expect, beforeEach, vi } from "vitest";
import { waitFor } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";

import { WorkforceWizard } from "../workforce-wizard";
import { WorkforceDashboard } from "../workforce-dashboard";
import { WorkforceAnalytics } from "../workforce-analytics";
import { WorkforceChat } from "../workforce-chat";
import { WorkforceBoard } from "../workforce-board";
import { WorkforceHistory } from "../workforce-history";
import { WorkforceSettings } from "../workforce-settings";

/**
 * The Workforce scroll contract.
 *
 * `WorkforceLayout` renders its `<main id="workforce-main">` as
 * `flex flex-1 flex-col min-h-0 overflow-hidden` — the shell deliberately does
 * NOT scroll, so that chat- and board-style pages can pin their own headers and
 * scroll only the region that should move. The cost of that choice is an
 * invisible contract: every page must supply its own scroll container, and a
 * page that forgets simply loses everything below the fold — no scrollbar, no
 * wheel response, and any footer controls unreachable.
 *
 * That is not hypothetical. The taskforce wizard shipped without one; as soon
 * as its step content outgrew the viewport, its own Back/Next buttons sat below
 * the cut with no way to reach them. Nothing failed — not a type, not a test,
 * not a lint rule — because the contract lived only in the other pages' habits.
 *
 * This is that contract, enforced. It asserts the WEAK form on purpose: SOME
 * element in the rendered tree scrolls vertically. It deliberately does not say
 * which element or where, because pages legitimately differ (the wizard scrolls
 * at its root; chat scrolls a transcript nested several levels down) and a
 * stricter assertion would fail on honest refactors.
 *
 * Class-based rather than computed-style, because jsdom loads no stylesheet:
 * `getComputedStyle(el).overflowY` is always "visible" here regardless of
 * Tailwind classes. The class strings ARE the styling contract in this
 * codebase, so matching them is the real check, not a proxy for one.
 */

/** Tailwind classes that make an element a vertical scroll container. */
const SCROLLER_PATTERN = /(^|\s)(overflow-y-auto|overflow-y-scroll|overflow-auto|overflow-scroll)(\s|$)/;

function findScrollers(container: HTMLElement): Element[] {
  return [...container.querySelectorAll("*")].filter((el) => {
    const cls = el.getAttribute("class");
    return !!cls && SCROLLER_PATTERN.test(cls);
  });
}

/**
 * Whether the page settled in a state that cannot outgrow the viewport.
 *
 * Rendered without fixtures, a data-driven page reaches its empty or loading
 * state — a centred card, a spinner — and those legitimately own no scroller;
 * Analytics is the case in this suite. Seeding each page's data instead would
 * bind this test to three unrelated response shapes, so it would then fail
 * whenever the analytics data model changed, which is precisely the brittleness
 * a layout guard must not have.
 *
 * So the contract is "scrolls OR provably cannot overflow", and the honest cost
 * is stated plainly: a page that regressed to no-scroller AND happens to render
 * empty here would pass. That is why the one page whose content always fills
 * the column — the wizard, the page this suite exists for — additionally pins
 * its scroller at the root, below.
 */
function isNonOverflowingState(container: HTMLElement): boolean {
  const text = container.textContent ?? "";
  return /No insights yet|Loading|No task forces|nothing here yet/i.test(text);
}

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}

describe("Workforce pages own their scroll container", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.ResizeObserver = ResizeObserverMock as unknown as typeof ResizeObserver;
  });

  /**
   * Route params matter: a board page rendered without its `:boardId` bails to
   * an empty/redirect state whose tree proves nothing, so each case carries the
   * path and pattern its real route uses.
   */
  const PAGES: readonly {
    name: string;
    path: string;
    pattern: string;
    element: () => React.ReactElement;
  }[] = [
    { name: "wizard", path: "/workforce/new", pattern: "/workforce/new", element: () => <WorkforceWizard /> },
    { name: "dashboard", path: "/workforce", pattern: "/workforce", element: () => <WorkforceDashboard /> },
    { name: "analytics", path: "/workforce/analytics", pattern: "/workforce/analytics", element: () => <WorkforceAnalytics /> },
    { name: "chat", path: "/workforce/chat", pattern: "/workforce/chat", element: () => <WorkforceChat /> },
    { name: "board", path: "/workforce/board-1", pattern: "/workforce/:boardId", element: () => <WorkforceBoard /> },
    {
      name: "history",
      path: "/workforce/board-1/history",
      pattern: "/workforce/:boardId/history",
      element: () => <WorkforceHistory />,
    },
    {
      name: "settings",
      path: "/workforce/board-1/settings",
      pattern: "/workforce/:boardId/settings",
      element: () => <WorkforceSettings />,
    },
  ];

  it.each(PAGES)(
    "$name scrolls, or is in a state that cannot overflow",
    async ({ name, path, pattern, element }) => {
      const { container } = renderPage(path, element(), pattern);

      await waitFor(() => {
        const scrolls = findScrollers(container).length > 0;
        expect(
          scrolls || isNonOverflowingState(container),
          `${name} rendered content with no vertical scroll container — the Workforce shell ` +
            `clips its main area, so everything below the fold is unreachable`,
        ).toBe(true);
      });
    },
  );

  /**
   * The regression itself, pinned at the exact level it broke: the wizard's own
   * root is the scroller. Its content is one long column with the step controls
   * at the bottom, so unlike chat or board there is no inner region that could
   * legitimately take over the job — if this moves, the buttons go unreachable
   * again.
   */
  it("the wizard scrolls at its ROOT, where its footer controls live", () => {
    const { container } = renderPage("/workforce/new", <WorkforceWizard />, "/workforce/new");

    const root = container.firstElementChild;
    expect(root).toBeTruthy();
    expect(root!.getAttribute("class") ?? "").toMatch(SCROLLER_PATTERN);
  });

  /** Guards the guard: a tree with no scroller must actually fail the check. */
  it("does not pass a page that forgot its scroller", () => {
    const { container } = renderPage(
      "/workforce/probe",
      <div className="max-w-3xl p-8">
        <p>tall content, nothing scrolls</p>
      </div>,
      "/workforce/probe",
    );

    expect(findScrollers(container)).toHaveLength(0);
  });
});
