import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ApprovalsPage } from "@/pages/approvals";

/**
 * The cross-group inbox exists to answer "what is waiting for me?" in one
 * place. For a group pause it previously answered "somewhere over there": no
 * verdict could be given, and the link out dropped the reviewer on the group at
 * version 1 with no idea which discussion was paused.
 */

const PAUSED = {
  conversationId: "gc-paused",
  groupId: "grp1",
  userId: "u1",
  pausedAt: "2026-06-01T10:00:00Z",
  pauseReason: "Phase requires approval",
  pauseType: "RULE",
};

const HUMAN_TURN = {
  ...PAUSED,
  conversationId: "gc-turn",
  pauseType: "HUMAN_TURN",
  pendingMemberId: "ana",
};

function serveGroupPendings(items: unknown[]) {
  server.use(
    http.get("*/groups/pending-approvals", () => HttpResponse.json(items)),
    http.get("*/agents/pending-approvals", () => HttpResponse.json([])),
  );
}

function render() {
  return renderPage("/manage/approvals", <ApprovalsPage />);
}

describe("ApprovalsPage — group pauses", () => {
  beforeEach(() => vi.clearAllMocks());

  it("approves a group phase from the queue, without navigating away", async () => {
    let body: { decision?: { verdict?: string } } | null = null;
    serveGroupPendings([PAUSED]);
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/approve", async ({ request }) => {
        body = (await request.json()) as typeof body;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    render();
    await userEvent.click(await screen.findByTestId("approve-gc-paused"));
    // Irreversible, so it confirms first — the same rule the 1:1 rows follow.
    await userEvent.click(await screen.findByRole("button", { name: /^approve$/i }));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.decision!.verdict).toBe("APPROVED");
  });

  it("rejects a group phase from the queue", async () => {
    let verdict: string | undefined;
    serveGroupPendings([PAUSED]);
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/approve", async ({ request }) => {
        verdict = ((await request.json()) as { decision: { verdict: string } }).decision.verdict;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    render();
    await userEvent.click(await screen.findByTestId("reject-gc-paused"));
    await userEvent.click(await screen.findByRole("button", { name: /^reject$/i }));

    await waitFor(() => expect(verdict).toBe("REJECTED"));
  });

  it("cancels a group discussion from the queue", async () => {
    let cancelled: string | null = null;
    serveGroupPendings([PAUSED]);
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled = String(params.gcId);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    render();
    await userEvent.click(await screen.findByTestId("cancel-gc-paused"));
    await userEvent.click(await screen.findByRole("button", { name: /cancel discussion|^cancel$/i }));

    await waitFor(() => expect(cancelled).toBe("gc-paused"));
  });

  it("links to the paused discussion at the group's current version", async () => {
    serveGroupPendings([PAUSED]);
    // A version other than 1: asserting only that SOME version is present
    // passes identically against a hardcoded 1, which is the whole point of
    // reading it from the descriptor.
    server.use(
      http.get("*/groupstore/groups/descriptors", () =>
        HttpResponse.json([
          {
            resource: "eddi://ai.labs.group/groupstore/groups/grp1?version=1",
            name: "Panel",
            createdOn: 1,
            lastModifiedOn: 1,
          },
          {
            resource: "eddi://ai.labs.group/groupstore/groups/grp1?version=4",
            name: "Panel",
            createdOn: 1,
            lastModifiedOn: 2,
          },
        ]),
      ),
      http.get("*/groupstore/groups/grp1", () =>
        HttpResponse.json({ id: "grp1", name: "Panel", style: "ROUND_TABLE", members: [] }),
      ),
    );
    render();

    const link = await screen.findByTestId("view-gc-paused");
    const href = link.getAttribute("href")!;
    // Without the conversation the reviewer lands on the group and has to find
    // the paused discussion; without the version they land on the original
    // configuration of any group that has ever been edited.
    expect(href).toContain("/manage/groups/grp1");
    expect(href).toContain("conversation=gc-paused");
    // The latest version, not the first and not a default.
    expect(href).toContain("version=4");
  });

  it("offers no link when the group's version cannot be established", async () => {
    // The group page defaults a missing version to 1 — for an edited group,
    // its ORIGINAL member list and name. This is the screen where someone
    // approves an action without the surrounding context, so showing them the
    // wrong context is worse than making them find the group themselves.
    serveGroupPendings([PAUSED]);
    server.use(http.get("*/groupstore/groups/descriptors", () => HttpResponse.json([])));
    render();

    expect(await screen.findByTestId("view-pending-gc-paused")).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(screen.queryByTestId("view-gc-paused")).not.toBeInTheDocument();
  });

  it("offers no verdict on a member's turn, which is not a decision", async () => {
    serveGroupPendings([HUMAN_TURN]);
    render();

    expect(await screen.findByTestId("view-gc-turn")).toBeInTheDocument();
    expect(screen.queryByTestId("approve-gc-turn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("reject-gc-turn")).not.toBeInTheDocument();
  });
});
