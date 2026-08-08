import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWorkspacePage } from "@/pages/group-workspace";

const ROUTE = "/manage/groups/g1/workspace";

/** GroupWorkspacePage reads groupId via useParams(":id"), so it needs a real
 *  route match — renderWithProviders alone leaves useParams() empty. */
function renderWorkspacePage() {
  return renderPage(ROUTE, <GroupWorkspacePage />, "/manage/groups/:id/workspace");
}

function mockWorkspace(overrides: Record<string, unknown> = {}) {
  return {
    id: "ws-g1", schemaVersion: 1, groupId: "g1",
    backlog: { tasks: [] },
    metrics: { discussions: 3, tasksVerified: 12, totalCost: 4.5, lastRunAt: "2026-08-01T09:00:00Z", perMemberStats: {} },
    cadences: [],
    runningDiscussionId: "",
    pulledTaskIds: [],
    created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z", revision: "0",
    ...overrides,
  };
}

describe("GroupWorkspacePage", () => {
  it("renders aggregate metrics from the workspace", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(mockWorkspace())),
    );
    renderWorkspacePage();

    await waitFor(() => {
      expect(screen.getByTestId("workspace-metrics")).toHaveTextContent("3");
    });
    expect(screen.getByTestId("workspace-metrics")).toHaveTextContent("12");
    expect(screen.getByTestId("workspace-metrics")).toHaveTextContent("4.50"); // formatUsd-shaped, $4.50
  });

  it("shows a running-discussion banner when a cadence run is in flight", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () =>
        HttpResponse.json(mockWorkspace({ runningDiscussionId: "gc-live-1" })),
      ),
    );
    renderWorkspacePage();

    await waitFor(() => {
      expect(screen.getByTestId("workspace-running-banner")).toBeInTheDocument();
    });
  });

  it("does not show the running banner when the workspace is idle", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(mockWorkspace())),
    );
    renderWorkspacePage();

    await waitFor(() => screen.getByTestId("workspace-metrics"));
    expect(screen.queryByTestId("workspace-running-banner")).not.toBeInTheDocument();
  });

  it("lists existing backlog tasks with status and priority", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () =>
        HttpResponse.json(mockWorkspace({
          backlog: {
            tasks: [
              { id: "t1", subject: "Refresh the changelog", description: "", status: "PENDING", assignedAgentId: null, assignedDisplayName: null, dependsOnIds: [], result: null, verificationNote: null, verified: false, priority: 2, createdAt: "2026-08-01T00:00:00Z", completedAt: null },
            ],
          },
        })),
      ),
    );
    renderWorkspacePage();

    await waitFor(() => {
      expect(screen.getByTestId("backlog-task-t1")).toHaveTextContent("Refresh the changelog");
    });
    expect(screen.getByTestId("backlog-task-t1")).toHaveTextContent("PENDING");
    expect(screen.getByTestId("backlog-task-t1")).toHaveTextContent("P2");
  });

  it("adds a backlog task and clears the form on success", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(mockWorkspace())),
    );
    renderWorkspacePage();
    await waitFor(() => screen.getByTestId("workspace-add-task"));

    expect(screen.getByTestId("workspace-add-task")).toBeDisabled();
    await user.type(screen.getByTestId("workspace-task-subject"), "Audit the onboarding flow");
    expect(screen.getByTestId("workspace-add-task")).not.toBeDisabled();

    await user.click(screen.getByTestId("workspace-add-task"));

    await waitFor(() => {
      expect(screen.getByTestId("workspace-task-subject")).toHaveValue("");
    });
  });

  it("surfaces the backend's 409 message when the backlog is full", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(mockWorkspace())),
      http.post("*/groupstore/groups/:groupId/workspace/backlog", () =>
        HttpResponse.json(
          { error: "The backlog already holds 200 tasks — complete or delete existing tasks before adding more" },
          { status: 409 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWorkspacePage();
    await waitFor(() => screen.getByTestId("workspace-add-task"));
    await user.type(screen.getByTestId("workspace-task-subject"), "One more task");
    await user.click(screen.getByTestId("workspace-add-task"));

    await waitFor(() => {
      expect(screen.getByTestId("workspace-task-error")).toHaveTextContent(
        "The backlog already holds 200 tasks",
      );
    });
  });

  it("adds a cadence and requires a non-blank cron expression", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(mockWorkspace())),
    );
    const user = userEvent.setup();
    renderWorkspacePage();
    await waitFor(() => screen.getByTestId("workspace-add-cadence"));

    expect(screen.getByTestId("workspace-add-cadence")).toBeDisabled();
    await user.type(screen.getByTestId("workspace-cron-input"), "0 9 * * MON");
    expect(screen.getByTestId("workspace-add-cadence")).not.toBeDisabled();

    await user.click(screen.getByTestId("workspace-add-cadence"));

    await waitFor(() => {
      expect(screen.getByTestId("workspace-cron-input")).toHaveValue("");
    });
  });

  // The backend applies no bounds to maxCostPerRun, so a negative value would
  // persist and then min() against the group ceiling — capping every run at a
  // nonsense budget. The UI is the only gate.
  it("refuses a negative max cost per run instead of sending it", async () => {
    let posted = 0;
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(mockWorkspace())),
      http.post("*/groupstore/groups/:groupId/workspace/cadences", () => {
        posted += 1;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWorkspacePage();
    await waitFor(() => screen.getByTestId("workspace-add-cadence"));

    await user.type(screen.getByTestId("workspace-cron-input"), "0 9 * * MON");
    fireEvent.change(screen.getByTestId("workspace-max-cost"), { target: { value: "-5" } });
    await user.click(screen.getByTestId("workspace-add-cadence"));

    await waitFor(() => {
      expect(screen.getByTestId("workspace-cadence-error")).toHaveTextContent("0 or more");
    });
    expect(posted).toBe(0);
  });

  it("lists an existing cadence and deletes it after confirmation", async () => {
    // Stateful mock: the GET must reflect the DELETE on refetch, or this test
    // cannot tell "the UI dropped it" apart from "the mock never removed it".
    let cadences = [
      { cadenceId: "cad-1", scheduleRef: "sched-1", inputTemplate: null, maxBacklogTasksPerRun: 5, maxCostPerRun: 2.5, createdBy: "alice" },
    ];
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () =>
        HttpResponse.json(mockWorkspace({ cadences })),
      ),
      http.delete("*/groupstore/groups/:groupId/workspace/cadences/:cadenceId", ({ params }) => {
        cadences = cadences.filter((c) => c.cadenceId !== params.cadenceId);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWorkspacePage();

    await waitFor(() => {
      expect(screen.getByTestId("cadence-cad-1")).toHaveTextContent("alice");
    });

    await user.click(screen.getByTestId("delete-cadence-cad-1"));
    // Destructive — must not fire on the trigger click alone.
    expect(screen.getByTestId("cadence-cad-1")).toBeInTheDocument();

    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /delete/i }));

    await waitFor(() => {
      expect(screen.queryByTestId("cadence-cad-1")).not.toBeInTheDocument();
    });
  });
});
