import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWorkspacePage } from "@/pages/group-workspace";

/**
 * Team learning loop on the workspace page: bets on backlog tasks (filing one,
 * the due-review section and its verdict) and the outcomes of a task.
 */

const ROUTE = "/manage/groups/g1/workspace";

function renderWorkspacePage() {
  return renderPage(ROUTE, <GroupWorkspacePage />, "/manage/groups/:id/workspace");
}

const PAST = "2020-01-01T00:00:00Z";
const FUTURE = "2999-01-01T00:00:00Z";

function task(id: string, extra: Record<string, unknown> = {}) {
  return {
    id, subject: `Task ${id}`, description: "", status: "PENDING", assignedAgentId: null, assignedDisplayName: null,
    dependsOnIds: [], result: null, verificationNote: null, verified: false, priority: 1,
    createdAt: "2026-06-01T00:00:00Z", completedAt: null, ...extra,
  };
}

function bet(reviewDate: string, reviewedAt: string | null = null) {
  return { hypothesis: "A founder post gets replies", metric: "replies_received", target: ">= 5", reviewDate, reviewedAt };
}

function workspace(tasks: unknown[]) {
  return {
    id: "ws-g1", schemaVersion: 1, groupId: "g1", backlog: { tasks, awardedBids: {} },
    metrics: { discussions: 0, tasksVerified: 0, totalCost: 0, lastRunAt: null, perMemberStats: {} },
    cadences: [], runningDiscussionId: "", pulledTaskIds: [],
    created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z", revision: "0",
  };
}

function useWorkspace(tasks: unknown[], dueTasks: unknown[] = [], outcomeConfig: unknown = null) {
  server.use(
    http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace(tasks))),
    http.get("*/groupstore/groups/:groupId/workspace/backlog/due-reviews", () => HttpResponse.json(dueTasks)),
    http.get("*/groupstore/groups/g1", () => HttpResponse.json({ name: "Growth Board", members: [], phases: [], outcomeConfig })),
  );
}

describe("bets on the workspace backlog", () => {
  it("keeps the bet fields out of the form until 'File as a bet' is ticked", async () => {
    useWorkspace([]);
    renderWorkspacePage();

    await screen.findByTestId("workspace-task-as-bet");
    expect(screen.queryByTestId("workspace-bet-fields")).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId("workspace-task-as-bet"));
    expect(screen.getByTestId("workspace-bet-fields")).toBeInTheDocument();
  });

  it("refuses a half-specified bet before any request", async () => {
    let posted = false;
    useWorkspace([]);
    server.use(http.post("*/groupstore/groups/:groupId/workspace/backlog", () => ((posted = true), HttpResponse.json({}, { status: 201 }))));
    renderWorkspacePage();

    fireEvent.change(await screen.findByTestId("workspace-task-subject"), { target: { value: "Launch post" } });
    await userEvent.click(screen.getByTestId("workspace-task-as-bet"));
    fireEvent.change(screen.getByTestId("workspace-bet-hypothesis"), { target: { value: "only a hypothesis" } });
    await userEvent.click(screen.getByTestId("workspace-add-task"));

    expect(await screen.findByTestId("workspace-task-error")).toBeInTheDocument();
    expect(posted).toBe(false);
  });

  it("files a bet with all its fields, and an ordinary task without any", async () => {
    const bodies: Record<string, unknown>[] = [];
    useWorkspace([]);
    server.use(
      http.post("*/groupstore/groups/:groupId/workspace/backlog", async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json(task("new"), { status: 201 });
      }),
    );
    renderWorkspacePage();

    fireEvent.change(await screen.findByTestId("workspace-task-subject"), { target: { value: "Launch post" } });
    await userEvent.click(screen.getByTestId("workspace-task-as-bet"));
    fireEvent.change(screen.getByTestId("workspace-bet-hypothesis"), { target: { value: "gets replies" } });
    fireEvent.change(screen.getByTestId("workspace-bet-metric"), { target: { value: "replies_received" } });
    fireEvent.change(screen.getByTestId("workspace-bet-target"), { target: { value: ">= 5" } });
    fireEvent.change(screen.getByTestId("workspace-bet-review-date"), { target: { value: "2026-12-01" } });
    await userEvent.click(screen.getByTestId("workspace-add-task"));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ subject: "Launch post", hypothesis: "gets replies", metric: "replies_received", target: ">= 5", reviewDate: "2026-12-01" });

    fireEvent.change(screen.getByTestId("workspace-task-subject"), { target: { value: "Plain task" } });
    await userEvent.click(screen.getByTestId("workspace-add-task"));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).not.toHaveProperty("hypothesis");
    expect(bodies[1]).not.toHaveProperty("reviewDate");
  });

  it("shows a task's bet on its backlog row, and none on an ordinary task", async () => {
    useWorkspace([task("b1", { bet: bet(FUTURE) }), task("p1")]);
    renderWorkspacePage();

    expect(await screen.findByTestId("bet-hypothesis-b1")).toHaveTextContent("A founder post gets replies");
    expect(screen.getByTestId("bet-b1")).toHaveTextContent("replies_received");
    expect(screen.getByTestId("bet-b1")).toHaveTextContent(">= 5");
    expect(screen.queryByTestId("bet-p1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("bet-mark-reviewed-b1")).not.toBeInTheDocument();
  });

  it("lists bets that are due, and shows nothing while none is", async () => {
    useWorkspace([task("b1", { bet: bet(PAST) })], [task("b1", { bet: bet(PAST) })]);
    const { unmount } = renderWorkspacePage();

    expect(await screen.findByTestId("workspace-due-reviews")).toBeInTheDocument();
    expect(screen.getByTestId("due-review-b1")).toHaveTextContent("Task b1");
    unmount();

    useWorkspace([task("b1", { bet: bet(FUTURE) })], []);
    renderWorkspacePage();
    await screen.findByTestId("bet-hypothesis-b1");
    expect(screen.queryByTestId("workspace-due-reviews")).not.toBeInTheDocument();
  });

  it("closes a due review, and reschedules one to a later date", async () => {
    const calls: unknown[] = [];
    useWorkspace([task("b1", { bet: bet(PAST) })], [task("b1", { bet: bet(PAST) })]);
    server.use(
      http.post("*/groupstore/groups/:groupId/workspace/backlog/:taskId/bet/review", async ({ params, request }) => {
        calls.push({ taskId: params.taskId, body: await request.json() });
        return HttpResponse.json(task("b1", { bet: bet(PAST, "2026-11-03T00:00:00Z") }));
      }),
    );
    renderWorkspacePage();

    const section = await screen.findByTestId("workspace-due-reviews");
    await userEvent.click(section.querySelector('[data-testid="bet-mark-reviewed-b1"]')!);
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]).toEqual({ taskId: "b1", body: { rescheduleTo: null } });

    await userEvent.click(section.querySelector('[data-testid="bet-reschedule-b1"]')!);
    fireEvent.change(section.querySelector('[data-testid="bet-reschedule-date-b1"]')!, { target: { value: "2026-12-15" } });
    await userEvent.click(section.querySelector('[data-testid="bet-reschedule-confirm-b1"]')!);
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).toEqual({ taskId: "b1", body: { rescheduleTo: "2026-12-15" } });
  });
});

describe("outcomes on the workspace backlog", () => {
  it("offers no outcomes control for a group whose outcomes are off", async () => {
    useWorkspace([task("t1")], [], null);
    renderWorkspacePage();

    await screen.findByTestId("backlog-task-t1");
    expect(screen.queryByTestId("task-outcomes-t1")).not.toBeInTheDocument();
  });

  it("loads a task's outcomes when its row is expanded, and says when there are none", async () => {
    useWorkspace([task("t1"), task("t2")], [], { maxPerSubject: 50, maxStored: 2000, retentionDays: 180, retroDigestSize: 10 });
    server.use(
      http.get("*/groupstore/groups/:groupId/outcomes", ({ request }) => {
        const subject = new URL(request.url).searchParams.get("subjectId");
        return HttpResponse.json(
          subject === "t1"
            ? [{ id: "o1", groupId: "g1", subjectType: "TASK", subjectId: "t1", kind: "reply_received", metric: "replies", value: 3, unit: "count",
                note: "from the CRM", source: "crm", recordedBy: "svc", occurredAt: "2026-11-02T08:00:00Z", recordedAt: "2026-11-02T08:00:01Z", idempotencyKey: null }]
            : [],
        );
      }),
    );
    renderWorkspacePage();

    await userEvent.click(await screen.findByTestId("task-outcomes-toggle-t1"));
    const line = await screen.findByTestId("outcome-o1");
    expect(line).toHaveTextContent("reply_received");
    expect(line).toHaveTextContent("replies = 3 count");
    expect(line).toHaveTextContent("crm");

    await userEvent.click(screen.getByTestId("task-outcomes-toggle-t2"));
    expect(await screen.findByTestId("task-outcomes-empty-t2")).toBeInTheDocument();
  });

  it("makes no outcome request for a row nobody expanded", async () => {
    let requested = false;
    useWorkspace([task("t1")], [], { maxPerSubject: 50, maxStored: 2000, retentionDays: 180, retroDigestSize: 10 });
    server.use(http.get("*/groupstore/groups/:groupId/outcomes", () => ((requested = true), HttpResponse.json([]))));
    renderWorkspacePage();

    await screen.findByTestId("task-outcomes-t1");
    expect(requested).toBe(false);
  });

  it("shows what came of approvals, per category, for a group with outcomes", async () => {
    useWorkspace([], [], { maxPerSubject: 50, maxStored: 2000, retentionDays: 180, retroDigestSize: 10 });
    server.use(
      http.get("*/hitl/analytics/outcomes", () =>
        HttpResponse.json({
          groupId: "g1", decisions: 4, outcomes: 3, truncated: false,
          categories: [{ category: "crm.notes", decisions: 4, decisionsWithOutcomes: 2, outcomes: 3, byKind: { reply_received: 2, meeting_booked: 1 } }],
        }),
      ),
    );
    renderWorkspacePage();

    const row = await screen.findByTestId("outcome-category-crm.notes");
    expect(row).toHaveTextContent("reply_received (2)");
    expect(row).toHaveTextContent("meeting_booked (1)");
  });

  it("hides the approvals panel when the caller may not read the analytics", async () => {
    useWorkspace([], [], { maxPerSubject: 50, maxStored: 2000, retentionDays: 180, retroDigestSize: 10 });
    server.use(http.get("*/hitl/analytics/outcomes", () => HttpResponse.json({ error: "no" }, { status: 403 })));
    renderWorkspacePage();

    await screen.findByTestId("workspace-backlog");
    expect(screen.queryByTestId("workspace-outcome-analytics")).not.toBeInTheDocument();
  });
});
