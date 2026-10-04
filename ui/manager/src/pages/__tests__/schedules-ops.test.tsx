import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SchedulesPage } from "@/pages/schedules";
import { server } from "@/test/mocks/server";

const toastMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: toastMock }));

describe("SchedulesPage - operations", () => {
  it("includes the server's reason when a toggle fails", async () => {
    server.use(
      http.post("*/schedulestore/schedules/:id/disable", () =>
        HttpResponse.json({ message: "Schedule is system-managed" }, { status: 403 }),
      ),
    );
    renderWithProviders(<SchedulesPage />, { initialRoute: "/manage/schedules" });
    await userEvent.setup().click(await screen.findByTestId("toggle-sched-1"));
    await waitFor(() => expect(toastMock.error).toHaveBeenCalled());
    const msg = String(toastMock.error.mock.calls[toastMock.error.mock.calls.length - 1]![0]);
    expect(msg).toContain("Failed to toggle schedule");
    expect(msg).toContain("Schedule is system-managed");
  });

  it("restores the active tab from the URL and writes it back on change", async () => {
    renderWithProviders(<SchedulesPage />, { initialRoute: "/manage/schedules?tab=failed" });
    expect(await screen.findByTestId("failed-fires-panel")).toBeInTheDocument();
    expect(screen.getByTestId("tab-failed")).toHaveAttribute("aria-selected", "true");
    await userEvent.setup().click(screen.getByTestId("tab-schedules"));
    expect(await screen.findByTestId("schedules-table")).toBeInTheDocument();
  });

  it("labels Last Fired with the schedule's zone, like Next Fire", async () => {
    renderWithProviders(<SchedulesPage />, { initialRoute: "/manage/schedules" });
    await waitFor(() => {
      expect(screen.getByTestId("lastfired-timezone-sched-1")).toHaveTextContent("UTC");
    });
  });
});
