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

function workspace() {
  return {
    id: "ws-g1", schemaVersion: 1, groupId: "g1",
    backlog: {
      tasks: [
        { id: "t1", subject: "Audit onboarding", description: "", status: "PENDING", priority: 3 },
      ],
      awardedBids: {},
    },
    metrics: { discussions: 0, tasksVerified: 0, totalCost: 0, lastRunAt: null, perMemberStats: {} },
    cadences: [],
    runningDiscussionId: "",
    pulledTaskIds: [],
    created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z", revision: "0",
  };
}

describe("GroupWorkspacePage — form clarity", () => {
  it("labels the task and cadence inputs, and explains P{n}", async () => {
    server.use(http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace())));
    renderWorkspacePage();
    await waitFor(() => screen.getByTestId("workspace-add-task"));

    expect(screen.getByLabelText("Subject")).toBe(screen.getByTestId("workspace-task-subject"));
    expect(screen.getByLabelText("Description")).toBe(screen.getByTestId("workspace-task-description"));
    expect(screen.getByLabelText("Prompt template")).toBe(screen.getByTestId("workspace-input-template"));

    // The badge says what P3 means, and the priority field says which way it sorts.
    expect(screen.getByTestId("backlog-task-priority-t1")).toHaveAttribute(
      "title",
      "Priority 3 — higher numbers are picked first",
    );
    expect(screen.getByTestId("workspace-task-priority")).toHaveAccessibleDescription(
      /Higher numbers are picked first/,
    );
    // No edit/delete endpoint exists for a backlog task; the page says so
    // instead of leaving the user hunting for the control.
    expect(screen.getByTestId("workspace-backlog-note")).toHaveTextContent(/cannot be edited or removed/);
  });
});

describe("GroupWorkspacePage — cadence time zone", () => {
  it("defaults to the viewer's own zone and sends the chosen one", async () => {
    let posted: { timeZone?: string } | null = null;
    server.use(
      http.get("*/groupstore/groups/:groupId/workspace", () => HttpResponse.json(workspace())),
      http.post("*/groupstore/groups/:groupId/workspace/cadences", async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWorkspacePage();
    await waitFor(() => screen.getByTestId("workspace-add-cadence"));

    const zone = screen.getByTestId("workspace-timezone-input") as HTMLSelectElement;
    expect(zone.tagName).toBe("SELECT");
    const own = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    expect(zone.value).toBe(own);
    expect(Array.from(zone.options).map((o) => o.value)).toContain("UTC");

    await user.selectOptions(zone, "Asia/Tokyo");
    await user.type(screen.getByTestId("workspace-cron-input"), "0 9 * * MON");
    await user.click(screen.getByTestId("workspace-add-cadence"));

    await waitFor(() => expect(posted).not.toBeNull());
    expect(posted!.timeZone).toBe("Asia/Tokyo");
  });
});
