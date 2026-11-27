import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { RowActions, RowActionsGroup } from "@eddi/app-kit/components/ui/row-actions";
import { ApprovalsPage } from "@/pages/approvals";

/**
 * The layout contract of an action cluster. jsdom has no layout engine, so this
 * asserts the classes that produce the layout (the same way the e2e/visual
 * checks read them back) rather than pixel positions: one end-aligned row, one
 * gap, whole groups wrap, and a button never shrinks or breaks its label.
 */
describe("RowActions", () => {
  it("is one wrapping, end-aligned row with a single gap", () => {
    render(
      <RowActions data-testid="ra">
        <button>One</button>
        <button>Two</button>
      </RowActions>,
    );
    const ra = screen.getByTestId("ra");
    expect(ra).toHaveAttribute("data-row-actions");
    for (const cls of ["flex", "flex-wrap", "items-center", "justify-end", "gap-2"]) {
      expect(ra).toHaveClass(cls);
    }
    expect(ra.className).toContain("[&>*]:shrink-0");
    expect(ra.className).toContain("[&>*]:whitespace-nowrap");
  });

  it("keeps a group on one line at one button height", () => {
    render(
      <RowActionsGroup data-testid="g">
        <button>A</button>
      </RowActionsGroup>,
    );
    const g = screen.getByTestId("g");
    expect(g).toHaveClass("flex-nowrap", "gap-2");
    expect(g.className).toContain("[&>*]:h-8");
  });
});

const GROUP = {
  conversationId: "gc-1",
  groupId: "grp1",
  userId: "u1",
  pausedAt: "2026-06-01T10:00:00Z",
  pauseReason: "Phase requires approval",
  pauseType: "RULE",
};
const SOLO = {
  conversationId: "conv-1",
  agentId: "a1",
  userId: "u1",
  pausedAt: "2026-06-01T10:00:00Z",
  pauseReason: "High value",
  pauseType: "RULE",
};

describe("ApprovalsPage — action cell layout", () => {
  it("lays a group row out like a conversation row: secondary group first, then the same decisions", async () => {
    server.use(
      http.get("*/groups/pending-approvals", () => HttpResponse.json([GROUP])),
      http.get("*/agents/pending-approvals", () => HttpResponse.json([SOLO])),
    );
    renderPage("/manage/approvals", <ApprovalsPage />);

    const solo = (await screen.findByTestId("approve-conv-1")).closest("[data-row-actions]")!;
    const group = (await screen.findByTestId("approve-gc-1")).closest("[data-row-actions]")!;

    const groups = (el: Element) => Array.from(el.querySelectorAll("[data-row-actions-group]"));
    // Conversation row: just the decisions.
    expect(groups(solo)).toHaveLength(1);
    // Group row: [Review, View] then the very same decisions.
    const [secondary, decisions] = groups(group) as [Element, Element];
    expect(secondary.querySelector('[data-testid="review-gc-1"]')).not.toBeNull();
    expect(secondary.querySelector('[data-testid^="view-"]')).not.toBeNull();
    const ids = (el: Element) => Array.from(el.querySelectorAll("button")).map((b) => b.dataset.testid?.split("-")[0]);
    expect(ids(decisions)).toEqual(["approve", "reject", "cancel"]);
    expect(ids(groups(solo)[0]!)).toEqual(["approve", "reject", "cancel"]);
    // Every action is the same small button, never a bare link styled by hand.
    await waitFor(() => {
      for (const el of group.querySelectorAll("button, a")) expect(el.className).toContain("h-8");
    });
  });
});
