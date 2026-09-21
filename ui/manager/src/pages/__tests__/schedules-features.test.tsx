import { describe, it, expect } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SchedulesPage } from "@/pages/schedules";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderSchedules() {
  return renderWithProviders(<SchedulesPage />, {
    initialRoute: "/manage/schedules",
  });
}

async function openCreateDialog(user: ReturnType<typeof userEvent.setup>) {
  const btn = await screen.findByTestId("create-schedule-btn");
  await user.click(btn);
  return screen.findByRole("dialog");
}

describe("SchedulesPage — one-time schedules", () => {
  it("creates a one-time schedule writing oneTimeAt and nothing else", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/schedulestore/schedules", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/schedulestore/schedules/one-time-1" },
        });
      })
    );

    const user = userEvent.setup();
    renderSchedules();
    const dialog = await openCreateDialog(user);

    // Switch to the One-time trigger option.
    await user.click(within(dialog).getByTestId("trigger-oneTime"));

    // Cron/interval inputs must not be present in one-time mode.
    expect(within(dialog).queryByTestId("cron-input")).not.toBeInTheDocument();
    expect(
      within(dialog).queryByTestId("heartbeat-input")
    ).not.toBeInTheDocument();
    const dt = within(dialog).getByTestId("onetime-input");
    expect(dt).toBeInTheDocument();

    await user.type(within(dialog).getByTestId("schedule-name-input"), "One shot");
    await user.type(within(dialog).getByTestId("agent-id-input"), "agent-x");
    fireEvent.change(dt, { target: { value: "2026-12-25T09:30" } });

    await user.click(within(dialog).getByTestId("schedule-submit-btn"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.triggerType).toBe("CRON");
    expect(typeof body!.oneTimeAt).toBe("string");
    expect(body!.oneTimeAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    // Exactly-one-of: neither cron nor heartbeat is sent.
    expect(body!.cronExpression).toBeUndefined();
    expect(body!.heartbeatIntervalSeconds).toBeUndefined();
  });
});

describe("SchedulesPage — cron helper", () => {
  it("shows a live human description and next-fire preview", async () => {
    const user = userEvent.setup();
    renderSchedules();
    const dialog = await openCreateDialog(user);

    const cron = within(dialog).getByTestId("cron-input");
    await user.clear(cron);
    await user.type(cron, "0 9 * * *");

    await waitFor(() => {
      expect(within(dialog).getByTestId("cron-description")).toHaveTextContent(
        /At 09:00/
      );
      expect(within(dialog).getByTestId("cron-next-preview")).toBeInTheDocument();
    });
  });

  it("flags an invalid cron expression inline", async () => {
    const user = userEvent.setup();
    renderSchedules();
    const dialog = await openCreateDialog(user);

    const cron = within(dialog).getByTestId("cron-input");
    await user.clear(cron);
    await user.type(cron, "not a cron");

    await waitFor(() => {
      expect(within(dialog).getByTestId("cron-error")).toBeInTheDocument();
    });
    // Submit stays disabled while the cron is invalid.
    expect(within(dialog).getByTestId("schedule-submit-btn")).toBeDisabled();
  });

  it("applies a cron preset when clicked", async () => {
    const user = userEvent.setup();
    renderSchedules();
    const dialog = await openCreateDialog(user);

    await user.click(within(dialog).getByTestId("cron-preset-hourly"));
    expect(within(dialog).getByTestId("cron-input")).toHaveValue("0 * * * *");
  });
});

describe("SchedulesPage — edit", () => {
  it("prefills the dialog from the row and saves via update", async () => {
    let putId = "";
    let putBody: Record<string, unknown> | null = null;
    server.use(
      http.put("*/schedulestore/schedules/:id", async ({ params, request }) => {
        putId = params.id as string;
        putBody = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, { status: 200 });
      })
    );

    const user = userEvent.setup();
    renderSchedules();

    await user.click(await screen.findByTestId("edit-sched-1"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Edit Schedule")).toBeInTheDocument();
    // Prefilled from sched-1.
    expect(within(dialog).getByTestId("schedule-name-input")).toHaveValue(
      "Daily Health Check"
    );
    expect(within(dialog).getByTestId("cron-input")).toHaveValue(
      "0 9 * * MON-FRI"
    );
    expect(within(dialog).getByTestId("timezone-select")).toHaveValue("UTC");

    const nameInput = within(dialog).getByTestId("schedule-name-input");
    await user.clear(nameInput);
    await user.type(nameInput, "Renamed Check");

    await user.click(within(dialog).getByTestId("schedule-submit-btn"));

    await waitFor(() => expect(putId).toBe("sched-1"));
    expect(putBody!.name).toBe("Renamed Check");
    expect(putBody!.triggerType).toBe("CRON");
    expect(putBody!.cronExpression).toBe("0 9 * * MON-FRI");
  });

  it("does not offer an edit action for HITL-timeout schedules", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json([
          {
            id: "hitl-1",
            name: "HITL Approval Timeout",
            triggerType: "CRON",
            agentId: "agent1",
            agentVersion: 0,
            environment: "production",
            oneTimeAt: new Date(Date.now() + 60000).toISOString(),
            message: "approval timeout",
            conversationStrategy: "new",
            enabled: true,
            fireStatus: "PENDING",
            failCount: 0,
            timeZone: "UTC",
            metadata: { hitlType: "hitl_timeout" },
          },
        ])
      )
    );

    renderSchedules();
    await waitFor(() => {
      expect(
        screen.getByTestId("hitl-schedule-badge-hitl-1")
      ).toBeInTheDocument();
    });
    // Neither edit nor fire is offered for a system-managed HITL timeout.
    expect(screen.queryByTestId("edit-hitl-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("fire-hitl-1")).not.toBeInTheDocument();
  });
});

describe("SchedulesPage — timezone", () => {
  it("persists the chosen timezone on create", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post("*/schedulestore/schedules", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/schedulestore/schedules/tz-1" },
        });
      })
    );

    const user = userEvent.setup();
    renderSchedules();
    const dialog = await openCreateDialog(user);

    await user.type(
      within(dialog).getByTestId("schedule-name-input"),
      "Vienna job"
    );
    await user.type(within(dialog).getByTestId("agent-id-input"), "agent-tz");
    fireEvent.change(within(dialog).getByTestId("timezone-select"), {
      target: { value: "Europe/Vienna" },
    });

    await user.click(within(dialog).getByTestId("schedule-submit-btn"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.timeZone).toBe("Europe/Vienna");
  });

  it("shows each schedule's timezone next to its next fire", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("timezone-sched-1")).toHaveTextContent("UTC");
    });
    expect(screen.getByTestId("timezone-sched-3")).toHaveTextContent(
      "Europe/Vienna"
    );
  });
});

describe("SchedulesPage — failed / dead-letter dashboard", () => {
  it("lists failed fires and retries a row", async () => {
    let retriedId = "";
    server.use(
      http.post("*/schedulestore/schedules/:id/retry", ({ params }) => {
        retriedId = params.id as string;
        return new HttpResponse(null, { status: 200 });
      })
    );

    const user = userEvent.setup();
    renderSchedules();

    await user.click(await screen.findByTestId("tab-failed"));

    const panel = await screen.findByTestId("failed-fires-panel");
    // Schedule name resolved from scheduleId, plus error + attempt columns.
    expect(within(panel).getByText("Failed Report")).toBeInTheDocument();
    expect(
      within(panel).getByText("Agent not deployed in production environment")
    ).toBeInTheDocument();
    expect(within(panel).getByText("3")).toBeInTheDocument(); // attemptNumber

    await user.click(within(panel).getByTestId("failed-retry-sched-3"));
    await waitFor(() => expect(retriedId).toBe("sched-3"));
  });

  it("dismisses a dead-lettered fire", async () => {
    let dismissedId = "";
    server.use(
      http.post("*/schedulestore/schedules/:id/dismiss", ({ params }) => {
        dismissedId = params.id as string;
        return new HttpResponse(null, { status: 200 });
      })
    );

    const user = userEvent.setup();
    renderSchedules();

    await user.click(await screen.findByTestId("tab-failed"));
    const panel = await screen.findByTestId("failed-fires-panel");

    await user.click(within(panel).getByTestId("failed-dismiss-sched-3"));
    await waitFor(() => expect(dismissedId).toBe("sched-3"));
  });

  it("shows an empty state when there are no failed fires", async () => {
    server.use(
      http.get("*/schedulestore/schedules/admin/failed", () =>
        HttpResponse.json([])
      )
    );

    const user = userEvent.setup();
    renderSchedules();

    await user.click(await screen.findByTestId("tab-failed"));
    await waitFor(() => {
      expect(screen.getByTestId("failed-fires-empty")).toBeInTheDocument();
    });
  });
});

describe("SchedulesPage — fire-now outcome", () => {
  it("confirms, then shows the returned fire log outcome inline", async () => {
    server.use(
      http.post("*/schedulestore/schedules/:id/fire", () =>
        HttpResponse.json({
          id: "manual-fire-1",
          scheduleId: "sched-1",
          fireTime: new Date().toISOString(),
          startedAt: new Date().toISOString(),
          completedAt: new Date().toISOString(),
          status: "FAILED",
          errorMessage: "Agent exploded",
          attemptNumber: 1,
          cost: 0.0042,
        })
      )
    );

    const user = userEvent.setup();
    renderSchedules();

    await user.click(await screen.findByTestId("fire-sched-1"));
    await user.click(await screen.findByTestId("fire-confirm-sched-1"));

    const outcome = await screen.findByTestId("fire-outcome-sched-1");
    expect(within(outcome).getByText(/Agent exploded/)).toBeInTheDocument();
    expect(within(outcome).getByText(/✗/)).toBeInTheDocument();
  });

  it("cancels the fire confirmation without firing", async () => {
    let fired = false;
    server.use(
      http.post("*/schedulestore/schedules/:id/fire", () => {
        fired = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    const user = userEvent.setup();
    renderSchedules();

    await user.click(await screen.findByTestId("fire-sched-1"));
    await user.click(await screen.findByTestId("fire-cancel-sched-1"));

    // Confirm dismissed; the fire endpoint was never called.
    expect(screen.queryByTestId("fire-confirm-sched-1")).not.toBeInTheDocument();
    expect(fired).toBe(false);
  });
});
