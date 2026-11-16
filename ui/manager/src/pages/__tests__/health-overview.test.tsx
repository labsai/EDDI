import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { portfolioFixture } from "@/test/mocks/health-fixtures";
import { healthPortfolioHandler } from "@/test/mocks/health-handlers";
import { HealthOverviewPage } from "../health-overview";

describe("HealthOverviewPage", () => {
  it("lists agents needs-me-first with their status, reason and key measures (H4)", async () => {
    server.use(healthPortfolioHandler("H4"));
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
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(6));
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
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(6));
    expect(screen.getAllByTestId("health-platform-incident")).toHaveLength(1);
  });

  it("says so when the backend has no status yet: by default (H3) no row has a status or a reason", async () => {
    renderWithProviders(<HealthOverviewPage />);
    await userEvent.click(await screen.findByTestId("health-overview-more"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(6));
    for (const row of screen.getAllByTestId("health-overview-row")) {
      expect(within(row).queryByTestId("health-status-badge")).toBeNull();
      expect(within(row).getByTestId("health-row-no-status")).toBeInTheDocument();
      expect(within(row).queryByTestId("health-row-reason")).toBeNull();
    }
  });

  it("filters by status (H4)", async () => {
    server.use(healthPortfolioHandler("H4"));
    renderWithProviders(<HealthOverviewPage />);
    await screen.findAllByTestId("health-overview-row");
    await userEvent.click(screen.getByTestId("health-status-filter-WATCH"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(1));
    expect(screen.getAllByTestId("health-overview-row")[0]).toHaveAttribute("data-agent-id", "health-silence");
    expect(screen.getByTestId("health-status-filter-WATCH")).toHaveAttribute("aria-pressed", "true");
  });

  it("shows an uncounted session total as a dash with the reason, never as zero", async () => {
    renderWithProviders(<HealthOverviewPage />);
    await userEvent.click(await screen.findByTestId("health-overview-more"));
    await waitFor(() => expect(screen.getAllByTestId("health-overview-row")).toHaveLength(6));
    const row = screen.getAllByTestId("health-overview-row").find((r) => r.getAttribute("data-agent-id") === "health-evaluating")!;
    expect(within(row).getByTestId("health-row-sessions-unknown")).toHaveTextContent("—");
    expect(within(row).getByTestId("health-row-sessions")).not.toHaveTextContent("0");
    expect(within(row).getByTestId("health-row-hint")).toHaveTextContent("90-day");
    // The key measure without a value says why, too.
    expect(within(row).getByTestId("health-row-measure-hint")).toBeInTheDocument();
    expect(within(row).getByTestId("health-row-measure")).toHaveTextContent("—");
  });

  it("reads incident days as days, whatever the time zone", async () => {
    renderWithProviders(<HealthOverviewPage />);
    const banner = await screen.findByTestId("health-platform-incident");
    // 2026-11-08 is a UTC day; shown in a zone behind UTC it would read as the 7th.
    expect(banner).toHaveTextContent(/\b8\b/);
    expect(banner).not.toHaveTextContent(/\b7\b/);
  });

  it("explains a status filter that matches nothing while statuses are not calculated", async () => {
    // The backend before H4: every status is null, so any status filter returns no items.
    server.use(
      http.get("*/health/agents", ({ request }) => {
        const filtered = new URL(request.url).searchParams.has("status");
        const items = filtered ? [] : portfolioFixture().items.map((i) => ({ ...i, status: null }));
        return HttpResponse.json({ items, platformIncidents: [], nextCursor: null });
      }),
    );
    renderWithProviders(<HealthOverviewPage />);
    expect(await screen.findAllByTestId("health-overview-row")).not.toHaveLength(0);
    expect(screen.queryByTestId("health-overview-empty-status")).toBeNull();
    await userEvent.click(screen.getByTestId("health-status-filter-WATCH"));
    expect(await screen.findByTestId("health-overview-empty")).toBeInTheDocument();
    expect(await screen.findByTestId("health-overview-empty-status")).toBeInTheDocument();
  });

  it("says only that no agents match when statuses are calculated and none has the chosen one", async () => {
    server.use(
      http.get("*/health/agents", ({ request }) => {
        const filtered = new URL(request.url).searchParams.has("status");
        return HttpResponse.json({ items: filtered ? [] : portfolioFixture("H4").items, platformIncidents: [], nextCursor: null });
      }),
    );
    renderWithProviders(<HealthOverviewPage />);
    expect(await screen.findAllByTestId("health-overview-row")).not.toHaveLength(0);
    await userEvent.click(screen.getByTestId("health-status-filter-WATCH"));
    expect(await screen.findByTestId("health-overview-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("health-overview-empty-status")).toBeNull();
  });
});
