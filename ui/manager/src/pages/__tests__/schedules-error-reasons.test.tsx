import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { SchedulesPage } from "@/pages/schedules";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));

const schedule = {
  id: "sched-a",
  name: "Nightly",
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
};

describe("SchedulesPage — failure toasts carry the backend's reason", () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear();
  });

  // Regression: every onError dropped the error, so a 403/409 read only as
  // "Failed to toggle schedule".
  it("shows why toggling a schedule was refused", async () => {
    server.use(
      http.get("*/schedulestore/schedules", () => HttpResponse.json([schedule])),
      http.post("*/schedulestore/schedules/:id/disable", () =>
        HttpResponse.json({ message: "Only the schedule's owner may disable it" }, { status: 403 }),
      ),
    );
    renderWithProviders(<SchedulesPage />, { initialRoute: "/manage/schedules" });
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("toggle-sched-a"));

    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1));
    const [, options] = vi.mocked(toast.error).mock.calls[0]!;
    expect((options as { description?: string }).description).toContain("Only the schedule's owner may disable it");
  });
});
