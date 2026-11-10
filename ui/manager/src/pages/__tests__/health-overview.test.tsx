import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { HealthOverviewPage } from "../health-overview";

describe("HealthOverviewPage", () => {
  it("lists agents needs-me-first with their status, reason and key measures", async () => {
    renderWithProviders(<HealthOverviewPage />);
    const rows = await screen.findAllByTestId("health-overview-row");
    expect(rows).toHaveLength(5);
    // The backend sorts by attention; the page keeps its order.
    expect(within(rows[0]!).getByText("Order Bot")).toBeInTheDocument();
    expect(within(rows[0]!).getByTestId("health-status-badge")).toHaveAttribute("data-status", "NEEDS_ATTENTION");
    expect(within(rows[0]!).getByTestId("health-row-reason")).toHaveTextContent("Resolution 41 %");
    expect(within(rows[0]!).getAllByTestId("health-row-measure")).toHaveLength(3);
    expect(within(rows[0]!).getByRole("link", { name: "Order Bot" })).toHaveAttribute("href", "/manage/agentview/health-attention#health");
  });

  it("shows the platform-incident banner and flags the affected agent", async () => {
    renderWithProviders(<HealthOverviewPage />);
    const banner = await screen.findByTestId("health-platform-incident");
    expect(banner).toHaveTextContent("RATE_LIMITED");
    expect(banner).toHaveTextContent("12 agents");
    const rows = await screen.findAllByTestId("health-overview-row");
    expect(within(rows[0]!).getByTestId("health-row-incident")).toBeInTheDocument();
    expect(within(rows[1]!).queryByTestId("health-row-incident")).toBeNull();
  });

  it("says so when the backend has no status yet", async () => {
    renderWithProviders(<HealthOverviewPage />);
    const row = (await screen.findAllByTestId("health-overview-row")).find((r) => r.textContent?.includes("Pre-status Agent"))!;
    expect(within(row).queryByTestId("health-status-badge")).toBeNull();
    expect(within(row).getByText("Status not available yet")).toBeInTheDocument();
  });

  it("filters by status", async () => {
    renderWithProviders(<HealthOverviewPage />);
    await screen.findAllByTestId("health-overview-row");
    await userEvent.click(screen.getByTestId("health-status-filter-WATCH"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(1));
    expect(screen.getByText("Quiet Resolver")).toBeInTheDocument();
    expect(screen.getByTestId("health-status-filter-WATCH")).toHaveAttribute("aria-pressed", "true");
  });
});
