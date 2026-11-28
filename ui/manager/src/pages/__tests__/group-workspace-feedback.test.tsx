import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { GroupWorkspacePage } from "@/pages/group-workspace";

/** Cadence retry feedback (GroupWorkspace.backlogFeedback) shows under its backlog task, as plain text. */

function task(id: string) {
  return { id, subject: `Task ${id}`, description: "Filed by a person", status: "PENDING", priority: 1 };
}

function seed(backlogFeedback?: unknown) {
  server.use(
    http.get("*/groupstore/groups/:groupId/workspace", () =>
      HttpResponse.json({
        id: "ws-g1", schemaVersion: 1, groupId: "g1", backlog: { tasks: [task("t1"), task("t2")], awardedBids: {} },
        metrics: { discussions: 0, tasksVerified: 0, totalCost: 0, lastRunAt: null, perMemberStats: {} },
        cadences: [], runningDiscussionId: "", pulledTaskIds: [], backlogFeedback,
        created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z", revision: "0",
      }),
    ),
  );
}

function renderWorkspacePage() {
  return renderPage("/manage/groups/g1/workspace", <GroupWorkspacePage />, "/manage/groups/:id/workspace");
}

describe("backlog retry feedback", () => {
  it("renders each task's feedback under it, as text and with the verified badge", async () => {
    seed([
      { taskId: "t1", runId: "run-1", text: "Needs a source <b>bold</b>", authorAgentId: "a1", verified: true },
      { taskId: "t1", runId: "run-2", text: "Second try", authorAgentId: null, verified: false },
      { taskId: "t2", runId: "run-3", text: "Other task", authorAgentId: null, verified: false },
    ]);
    renderWorkspacePage();

    const first = await screen.findByTestId("backlog-feedback-t1-0");
    expect(first).toHaveTextContent("run-1");
    expect(first).toHaveTextContent("Needs a source <b>bold</b>");
    expect(first.querySelector("b")).toBeNull();
    expect(screen.getByTestId("backlog-feedback-verified-t1-0")).toBeInTheDocument();
    expect(screen.getByTestId("backlog-feedback-t1-1")).toHaveTextContent("Second try");
    expect(screen.queryByTestId("backlog-feedback-verified-t1-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("backlog-feedback-t2-0")).toHaveTextContent("Other task");
    expect(screen.queryByTestId("backlog-feedback-t2-1")).not.toBeInTheDocument();
  });

  it("renders no feedback block for a workspace that has none", async () => {
    seed(undefined);
    renderWorkspacePage();

    await screen.findByTestId("backlog-task-t1");
    expect(screen.queryByTestId("backlog-feedback-t1-0")).not.toBeInTheDocument();
  });
});
