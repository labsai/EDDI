import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWorkspacePage } from "@/pages/group-workspace";

function renderWorkspacePage() {
  return renderPage("/manage/groups/g1/workspace", <GroupWorkspacePage />, "/manage/groups/:id/workspace");
}

const ORIGIN = {
  handoffId: "h-1",
  sourceKind: "GROUP",
  sourceId: "radar-group",
  sourceVersion: null,
  sourceConversationId: "gc-9",
  trigger: "DECISION",
  decisionRef: "VOTE@Vote",
  hop: 1,
  lineage: ["group:radar-group"],
  dedupeKey: "decision:gc-9:1:0",
  filedAt: "2026-11-02T09:00:00Z",
  intake: "AUTO",
};

function workspace(withHandoffs: boolean) {
  return {
    id: "ws-g1", schemaVersion: 1, groupId: "g1",
    backlog: {
      tasks: [
        { id: "t1", subject: "Research ACME", description: "", status: "PENDING", priority: 7 },
        { id: "t2", subject: "Fix the flaky test", description: "", status: "PENDING", priority: 1 },
      ],
      awardedBids: {},
    },
    metrics: { discussions: 0, tasksVerified: 0, totalCost: 0, lastRunAt: null, perMemberStats: {} },
    cadences: [],
    runningDiscussionId: "",
    pulledTaskIds: [],
    handoffs: withHandoffs
      ? {
          inbox: [
            {
              handoffId: "h-2", subject: "Warm lead: Globex", description: "wants 10 seats", priority: 5,
              origin: { ...ORIGIN, handoffId: "h-2", sourceKind: "AGENT", sourceId: "agent-9", trigger: "TOOL", intake: "PENDING" },
            },
          ],
          origins: { t1: ORIGIN },
        }
      : undefined,
    created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z", revision: "0",
  };
}

describe("GroupWorkspacePage — cross-team handoff", () => {
  it("shows the intake inbox above the backlog and marks handed-off rows", async () => {
    server.use(http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace(true))));
    renderWorkspacePage();

    const inbox = await screen.findByTestId("workspace-handoff-inbox");
    expect(inbox).toHaveTextContent("Warm lead: Globex");
    const backlog = screen.getByTestId("workspace-backlog");
    expect(inbox.compareDocumentPosition(backlog) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    // The handed-off task carries its origin; the locally authored one does not.
    expect(screen.getByTestId("handoff-origin-h-1")).toHaveTextContent("from radar-group");
    expect(screen.getByTestId("backlog-task-t1")).toContainElement(screen.getByTestId("handoff-origin-h-1"));
    expect(screen.getByTestId("backlog-task-t2").querySelector('[data-testid^="handoff-origin-"]')).toBeNull();
  });

  it("renders as before for a backend that has no handoff state at all", async () => {
    server.use(http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace(false))));
    renderWorkspacePage();

    await waitFor(() => screen.getByTestId("workspace-backlog"));
    expect(screen.queryByTestId("workspace-handoff-inbox")).not.toBeInTheDocument();
    expect(screen.queryByTestId("handoff-origin-h-1")).not.toBeInTheDocument();
  });

  it("deletes a backlog task after confirmation", async () => {
    let deleted: string | null = null;
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace(true))),
      http.delete("*/groupstore/groups/:groupId/workspace/backlog/:taskId", ({ params }) => {
        deleted = String(params.taskId);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWorkspacePage();

    await user.click(await screen.findByTestId("backlog-task-delete-t2"));
    expect(deleted).toBeNull();
    await user.click(await screen.findByRole("button", { name: "Delete" }));

    await waitFor(() => expect(deleted).toBe("t2"));
  });
});
