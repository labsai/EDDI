import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { SchedulesPage } from "@/pages/schedules";

function renderSchedules() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={["/manage/schedules"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <SchedulesPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("SchedulesPage", () => {
  // ── Page structure ──────────────────────────────────────────────────────

  it("renders the page title", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByText("Schedules")).toBeInTheDocument();
    });
  });

  it("renders the page subtitle", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(
        screen.getByText(/Manage scheduled agent triggers/)
      ).toBeInTheDocument();
    });
  });

  it("renders the create schedule button", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("create-schedule-btn")).toBeInTheDocument();
    });
  });

  // ── Status cards ────────────────────────────────────────────────────────

  it("renders total schedules card", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("schedules-total-card")).toBeInTheDocument();
    });
  });

  it("renders active schedules card", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("schedules-active-card")).toBeInTheDocument();
    });
  });

  it("renders failed schedules card", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("schedules-failed-card")).toBeInTheDocument();
    });
  });

  it("renders next fire card", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(
        screen.getByTestId("schedules-next-fire-card")
      ).toBeInTheDocument();
    });
  });

  it("shows correct total count from mock data", async () => {
    renderSchedules();
    await waitFor(() => {
      const card = screen.getByTestId("schedules-total-card");
      expect(within(card).getByText("3")).toBeInTheDocument();
    });
  });

  it("shows correct active count from mock data", async () => {
    renderSchedules();
    await waitFor(() => {
      const card = screen.getByTestId("schedules-active-card");
      expect(within(card).getByText("2")).toBeInTheDocument();
    });
  });

  it("shows correct failed count from mock data", async () => {
    renderSchedules();
    await waitFor(() => {
      const card = screen.getByTestId("schedules-failed-card");
      // sched-3 is DEAD_LETTERED
      expect(within(card).getByText("1")).toBeInTheDocument();
    });
  });

  // ── Schedule table ──────────────────────────────────────────────────────

  it("renders the schedule table", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("schedules-table")).toBeInTheDocument();
    });
  });

  it("shows schedule names from mock data", async () => {
    renderSchedules();
    // Use findByText with extended timeout to wait for async data
    expect(await screen.findByText("Daily Health Check", {}, { timeout: 3000 })).toBeInTheDocument();
    // "Heartbeat Monitor" appears agenth in the table and the Next Fire card (soonest schedule)
    expect(screen.getAllByText("Heartbeat Monitor").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("Failed Report")).toBeInTheDocument();
  });

  it("shows cron type badge", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getAllByText("Cron").length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows heartbeat type badge", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByText("Heartbeat")).toBeInTheDocument();
    });
  });

  it("shows cron expression in table", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByText("0 9 * * MON-FRI")).toBeInTheDocument();
    });
  });

  it("shows heartbeat interval in table", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByText("Every 300s")).toBeInTheDocument();
    });
  });

  it("shows status badges", async () => {
    renderSchedules();
    await waitFor(() => {
      // sched-1 and sched-2 are enabled + COMPLETED/PENDING → Active
      expect(screen.getAllByText("Active").length).toBeGreaterThanOrEqual(1);
      // sched-3 is DEAD_LETTERED (error state shown even though disabled)
      expect(screen.getByText("Dead-Lettered")).toBeInTheDocument();
    });
  });

  it("shows cron description", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(
        screen.getByText("At 09:00 AM, Monday through Friday")
      ).toBeInTheDocument();
    });
  });

  // ── Action buttons ──────────────────────────────────────────────────────

  it("renders toggle buttons for each schedule", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("toggle-sched-1")).toBeInTheDocument();
      expect(screen.getByTestId("toggle-sched-2")).toBeInTheDocument();
      expect(screen.getByTestId("toggle-sched-3")).toBeInTheDocument();
    });
  });

  it("renders fire buttons for each schedule", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("fire-sched-1")).toBeInTheDocument();
      expect(screen.getByTestId("fire-sched-2")).toBeInTheDocument();
      expect(screen.getByTestId("fire-sched-3")).toBeInTheDocument();
    });
  });

  it("renders retry button only for dead-lettered schedules", async () => {
    renderSchedules();
    await waitFor(() => {
      // sched-3 is DEAD_LETTERED, should have retry
      expect(screen.getByTestId("retry-sched-3")).toBeInTheDocument();
    });
    // sched-1 and sched-2 should NOT have retry
    expect(screen.queryByTestId("retry-sched-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("retry-sched-2")).not.toBeInTheDocument();
  });

  it("renders delete buttons for each schedule", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(screen.getByTestId("delete-sched-1")).toBeInTheDocument();
      expect(screen.getByTestId("delete-sched-2")).toBeInTheDocument();
      expect(screen.getByTestId("delete-sched-3")).toBeInTheDocument();
    });
  });

  // ── Fire history expandable section ─────────────────────────────────────

  it("renders fire history buttons", async () => {
    renderSchedules();
    await waitFor(() => {
      expect(
        screen.getAllByText("Fire History").length
      ).toBeGreaterThanOrEqual(1);
    });
  });

  // ── Create dialog ──────────────────────────────────────────────────────

  it("opens Create Schedule dialog on button click", async () => {
    renderSchedules();

    // Wait for mocked data to load and button to appear
    const btn = await screen.findByTestId("create-schedule-btn");
    // Use fireEvent for a synchronous click
    btn.click();

    // Wait for dialog to appear
    const dialog = await screen.findByRole("dialog", {}, { timeout: 3000 });
    expect(dialog).toBeInTheDocument();
    expect(
      within(dialog).getByText("Create Schedule")
    ).toBeInTheDocument();
  });

  it("shows cron/heartbeat trigger type tabs in dialog", async () => {
    renderSchedules();

    const btn = await screen.findByTestId("create-schedule-btn");
    btn.click();

    const dialog = await screen.findByRole("dialog", {}, { timeout: 3000 });
    expect(within(dialog).getByText("Cron")).toBeInTheDocument();
    expect(within(dialog).getByText("Heartbeat")).toBeInTheDocument();
  });

  it("Create button is disabled when form is empty", async () => {
    renderSchedules();

    const btn = await screen.findByTestId("create-schedule-btn");
    btn.click();

    const dialog = await screen.findByRole("dialog", {}, { timeout: 3000 });
    const createBtn = within(dialog).getByText("Create");
    expect(createBtn).toBeDisabled();
  });

  it("shows fail count indicator for failed schedules", async () => {
    renderSchedules();
    await waitFor(() => {
      // sched-3 has failCount: 3
      expect(screen.getByText("×3")).toBeInTheDocument();
    });
  });
});
