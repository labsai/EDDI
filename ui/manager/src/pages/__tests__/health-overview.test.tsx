import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { HealthOverviewPage } from "../health-overview";

describe("HealthOverviewPage", () => {
  it("lists agents needs-me-first with their status, reason and key measures", async () => {
    renderWithProviders(<HealthOverviewPage />);
    const rows = await screen.findAllByTestId("health-overview-row");
    // The backend sorts by attention; the page keeps its order.
    expect(rows[0]).toHaveAttribute("data-agent-id", "health-attention");
    expect(within(rows[0]!).getByTestId("health-status-badge")).toHaveAttribute("data-status", "NEEDS_ATTENTION");
    expect(within(rows[0]!).getByTestId("health-row-reason")).toHaveTextContent("Resolution 41 %");
    expect(within(rows[0]!).getAllByTestId("health-row-measure")).toHaveLength(3);
    expect(within(rows[0]!).getByRole("link")).toHaveAttribute("href", "/manage/agentview/health-attention#health");
  });

  it("follows the cursor: Load more appends the next page and goes away at the end", async () => {
    renderWithProviders(<HealthOverviewPage />);
    expect(await screen.findAllByTestId("health-overview-row")).toHaveLength(3);
    await userEvent.click(screen.getByTestId("health-overview-more"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(5));
    expect(screen.queryByTestId("health-overview-more")).toBeNull();
  });

  it("shows the platform-incident banner once, and flags the affected agent", async () => {
    renderWithProviders(<HealthOverviewPage />);
    const banner = await screen.findByTestId("health-platform-incident");
    expect(banner).toHaveTextContent("RATE_LIMITED");
    const rows = await screen.findAllByTestId("health-overview-row");
    expect(within(rows[0]!).getByTestId("health-row-incident")).toBeInTheDocument();
    expect(within(rows[1]!).queryByTestId("health-row-incident")).toBeNull();
    await userEvent.click(screen.getByTestId("health-overview-more"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(5));
    expect(screen.getAllByTestId("health-platform-incident")).toHaveLength(1);
  });

  it("says so when the backend has no status yet", async () => {
    renderWithProviders(<HealthOverviewPage />);
    await userEvent.click(await screen.findByTestId("health-overview-more"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(5));
    const row = screen.getAllByTestId("health-overview-row").find((r) => r.getAttribute("data-agent-id") === "health-preh4")!;
    expect(within(row).queryByTestId("health-status-badge")).toBeNull();
    expect(within(row).getByTestId("health-row-no-status")).toBeInTheDocument();
  });

  it("filters by status", async () => {
    renderWithProviders(<HealthOverviewPage />);
    await screen.findAllByTestId("health-overview-row");
    await userEvent.click(screen.getByTestId("health-status-filter-WATCH"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(1));
    expect(screen.getAllByTestId("health-overview-row")[0]).toHaveAttribute("data-agent-id", "health-silence");
    expect(screen.getByTestId("health-status-filter-WATCH")).toHaveAttribute("aria-pressed", "true");
  });
});
