import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWorkspacePage } from "@/pages/group-workspace";

/**
 * A cadence's `Cadence` record carries a `scheduleRef` and nothing about when it
 * runs — the cron expression and time zone live only on the paired row in the
 * schedule store. The list showed a column of UUIDs, so you could not tell which
 * cadence was the Monday 9am run, or which of three to delete.
 */

const CADENCE = {
  cadenceId: "cad-1",
  scheduleRef: "sched-1",
  inputTemplate: null,
  maxBacklogTasksPerRun: 5,
  maxCostPerRun: null,
  createdBy: "alice",
};

function workspace(overrides: Record<string, unknown> = {}) {
  return {
    id: "ws-g1",
    schemaVersion: 1,
    groupId: "g1",
    backlog: { tasks: [], awardedBids: {} },
    metrics: {
      discussions: 1,
      tasksVerified: 0,
      totalCost: 0,
      lastRunAt: null,
      perMemberStats: {},
    },
    cadences: [CADENCE],
    runningDiscussionId: "",
    pulledTaskIds: [],
    created: "2026-06-01T00:00:00Z",
    lastModified: "2026-06-01T00:00:00Z",
    revision: "0",
    ...overrides,
  };
}

function serve(schedules: unknown[]) {
  server.use(
    http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace())),
    http.get("*/schedulestore/schedules", () => HttpResponse.json(schedules)),
  );
}

function render() {
  return renderPage(
    "/manage/groups/g1/workspace",
    <GroupWorkspacePage />,
    "/manage/groups/:id/workspace",
  );
}

describe("GroupWorkspacePage — cadences say when they run", () => {
  it("describes the cadence's schedule instead of printing its id", async () => {
    serve([
      {
        id: "sched-1",
        name: "Weekly",
        triggerType: "CRON",
        agentId: "a1",
        agentVersion: 0,
        environment: "production",
        cronExpression: "0 9 * * MON",
        timeZone: "Europe/Berlin",
        message: "",
        enabled: true,
        fireStatus: "IDLE",
        failCount: 0,
      },
    ]);
    render();

    const when = await screen.findByTestId("cadence-when-cad-1");
    // A human reading, the raw expression, and the zone — enough to tell two
    // cadences apart before deleting one.
    expect(when).toHaveTextContent(/monday/i);
    const row = screen.getByTestId("cadence-cad-1");
    expect(row).toHaveTextContent("0 9 * * MON");
    expect(row).toHaveTextContent("Europe/Berlin");
  });

  it("says so when the paired schedule cannot be read", async () => {
    // Never render a blank where a schedule should be: the cadence still exists
    // and still fires.
    serve([]);
    render();
    expect(await screen.findByTestId("cadence-when-cad-1")).toHaveTextContent(
      /unavailable/i,
    );
  });
});

describe("GroupWorkspacePage — cron input", () => {
  it("describes the expression as it is typed", async () => {
    serve([]);
    render();
    const input = await screen.findByTestId("workspace-cron-input");
    await userEvent.type(input, "0 9 * * MON");
    await waitFor(() =>
      expect(screen.getByTestId("workspace-cron-hint")).toHaveTextContent(/monday/i),
    );
  });

  it("refuses an unparseable expression before the round trip", async () => {
    let posted = false;
    serve([]);
    server.use(
      http.post("*/groupstore/groups/:groupId/workspace/cadences", () => {
        posted = true;
        return HttpResponse.json(CADENCE);
      }),
    );
    render();

    await userEvent.type(await screen.findByTestId("workspace-cron-input"), "not a cron");
    await userEvent.click(screen.getByTestId("workspace-add-cadence"));

    expect(await screen.findByTestId("workspace-cadence-error")).toHaveTextContent(
      /not a valid cron/i,
    );
    expect(posted).toBe(false);
  });
});

describe("GroupWorkspacePage — per-member stats", () => {
  it("names members rather than printing their agent ids", async () => {
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () =>
        HttpResponse.json(
          workspace({
            metrics: {
              discussions: 1,
              tasksVerified: 3,
              totalCost: 0,
              lastRunAt: null,
              perMemberStats: { "agent-uuid-1": { tasksVerified: 3, tasksFailed: 1 } },
            },
          }),
        ),
      ),
      http.get("*/schedulestore/schedules", () => HttpResponse.json([])),
      http.get("*/groupstore/groups/g1", () =>
        HttpResponse.json({
          id: "g1",
          name: "Standing team",
          style: "ROUND_TABLE",
          members: [{ agentId: "agent-uuid-1", displayName: "Ana" }],
        }),
      ),
    );
    render();

    expect(await screen.findByText("Ana")).toBeInTheDocument();
    // The id stays as the tooltip — it is what the API and the logs use.
    expect(screen.getByTitle("agent-uuid-1")).toBeInTheDocument();
  });
});
