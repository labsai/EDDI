import { describe, it, expect, afterEach, vi } from "vitest";
import { toast } from "sonner";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SchedulesPage } from "@/pages/schedules";
import { invalidScheduleReason } from "@/lib/api/schedules";
import { ApiClientError } from "@/lib/api-client";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderSchedules() {
  return renderWithProviders(<SchedulesPage />, { initialRoute: "/manage/schedules" });
}

function scheduleRow(overrides: Record<string, unknown>) {
  return {
    triggerType: "CRON",
    agentId: "agent1",
    agentVersion: 0,
    environment: "production",
    cronExpression: "0 3 * * *",
    message: "tick",
    conversationStrategy: "new",
    enabled: true,
    fireStatus: "PENDING",
    failCount: 0,
    timeZone: "UTC",
    ...overrides,
  };
}

afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});

// ── EDDI 6.6 disabledReason ────────────────────────────────────────────────

describe("SchedulesPage — why a schedule is disabled", () => {
  it("explains an undeploy: it comes back by itself at the next deploy", async () => {
    renderSchedules();
    const notice = await screen.findByTestId("disabled-reason-sched-4");
    expect(notice).toHaveAttribute("data-reason", "agent-undeployed");
    expect(notice).toHaveTextContent("Agent undeployed");
    expect(notice).toHaveTextContent(/next successful deploy/);
  });

  it("explains a revoked access, names the creator and says how to fix it", async () => {
    renderSchedules();
    const notice = await screen.findByTestId("disabled-reason-sched-5");
    expect(notice).toHaveAttribute("data-reason", "access-revoked");
    expect(notice).toHaveTextContent("Access revoked");
    expect(notice).toHaveTextContent(/\(bob\)/);
    expect(notice).toHaveTextContent(/then enable it/);
  });

  it("a disabled schedule without a reason was disabled by a person — and may be a 6.5 undeploy needing one enable", async () => {
    renderSchedules();
    const notice = await screen.findByTestId("disabled-by-person-sched-3");
    expect(notice).toHaveTextContent(/before EDDI 6.6/);
    expect(screen.queryByTestId("disabled-reason-sched-3")).not.toBeInTheDocument();
  });

  it("a finished one-shot schedule is not described as disabled by a person", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json([
          scheduleRow({ id: "sched-done", name: "Run once", enabled: false, fireStatus: "COMPLETED", disabledReason: null }),
        ]),
      ),
    );
    renderSchedules();
    await screen.findByText("Run once");
    expect(screen.queryByTestId("disabled-by-person-sched-done")).not.toBeInTheDocument();
    expect(screen.queryByTestId("disabled-reason-sched-done")).not.toBeInTheDocument();
  });

  it("an enabled schedule carries no disabled notice, even with a stale reason", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json([scheduleRow({ id: "on-1", name: "On", enabled: true, disabledReason: "agent-undeployed" })]),
      ),
    );
    renderSchedules();
    await screen.findByText("On");
    expect(screen.queryByTestId("disabled-reason-on-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("disabled-by-person-on-1")).not.toBeInTheDocument();
  });

  it("shows an unknown reason verbatim rather than hiding it", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () =>
        HttpResponse.json([scheduleRow({ id: "x-1", name: "X", enabled: false, disabledReason: "quota-exceeded" })]),
      ),
    );
    renderSchedules();
    expect(await screen.findByTestId("disabled-reason-x-1")).toHaveTextContent("quota-exceeded");
  });
});

// ── EDDI 6.6 400 {"error":"invalid_schedule","message":…} ──────────────────

describe("SchedulesPage — the server's reason for refusing a save", () => {
  it("shows the 400 message inside the edit dialog and keeps the dialog open", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () => HttpResponse.json([scheduleRow({ id: "chat-1", name: "Nightly" })])),
      http.put("*/schedulestore/schedules/:id", () =>
        HttpResponse.json(
          { error: "invalid_schedule", message: "Cron expression must have exactly 5 fields (minute hour day month weekday), got 6" },
          { status: 400 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderSchedules();
    await user.click(await screen.findByTestId("edit-chat-1"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByTestId("schedule-submit-btn"));

    const error = await within(dialog).findByTestId("schedule-server-error");
    expect(error).toHaveAttribute("role", "alert");
    expect(error).toHaveTextContent("Cron expression must have exactly 5 fields");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("a non-400 failure stays a toast, not an inline reason", async () => {
    const toastError = vi.spyOn(toast, "error");
    server.use(
      http.get("*/schedulestore/schedules", () => HttpResponse.json([scheduleRow({ id: "chat-1", name: "Nightly" })])),
      http.put("*/schedulestore/schedules/:id", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
    );
    const user = userEvent.setup();
    renderSchedules();
    await user.click(await screen.findByTestId("edit-chat-1"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByTestId("schedule-submit-btn"));
    await waitFor(() => expect(toastError).toHaveBeenCalledWith("Failed to update schedule"));
    expect(within(dialog).queryByTestId("schedule-server-error")).not.toBeInTheDocument();
  });
});

describe("invalidScheduleReason", () => {
  it("returns the message of a 400 and nothing else", () => {
    expect(invalidScheduleReason(new ApiClientError(400, "bad cron"))).toBe("bad cron");
    expect(invalidScheduleReason(new ApiClientError(500, "boom"))).toBeNull();
    expect(invalidScheduleReason(new Error("x"))).toBeNull();
    expect(invalidScheduleReason(new ApiClientError(400, "  "))).toBeNull();
  });
});
