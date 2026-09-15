import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { WorkforceAnalytics } from "@/pages/workforce/workforce-analytics";

/**
 * Reported bug: "if I click Insights and I have no insights available yet,
 * there is no Back button to get back to the Workforce dashboard".
 *
 * The header holding the back link lived inside the success return, so the
 * loading, error and empty branches — the states a fresh deployment actually
 * starts in — rendered no way back. On tablet the sidebar is behind a drawer,
 * which left the user stuck.
 */

const analyticsMock = vi.fn();
vi.mock("@/hooks/use-workforce-analytics", () => ({
  useWorkforceAnalytics: () => analyticsMock(),
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

const BASE = {
  isLoading: false,
  hasError: false,
  totalDiscussions: 0,
  groupCount: 0,
  outcomeCounts: {},
  styleCounts: {},
  agentStats: [],
  discussions: [],
  activityByDay: [],
  phaseTotals: [],
  skillCoverage: [],
  avgDurationMs: 0,
  totalCost: 0,
  activeAgents: 0,
};

function renderAnalytics() {
  return renderPage("/workforce/analytics", <WorkforceAnalytics />, "/workforce/analytics");
}

async function expectBackLink() {
  const back = await screen.findByRole("link", { name: /back/i });
  expect(back).toHaveAttribute("href", "/workforce");
}

beforeEach(() => {
  analyticsMock.mockReset();
});

describe("Insights page keeps a route back to the dashboard", () => {
  it("in the empty state (the reported case)", async () => {
    analyticsMock.mockReturnValue({ ...BASE, totalDiscussions: 0 });
    renderAnalytics();
    await waitFor(() => expect(screen.getByText(/no insights yet/i)).toBeInTheDocument());
    await expectBackLink();
  });

  it("in the empty state when task forces exist but have no discussions", async () => {
    analyticsMock.mockReturnValue({ ...BASE, totalDiscussions: 0, groupCount: 3 });
    renderAnalytics();
    await expectBackLink();
  });

  it("in the error state", async () => {
    analyticsMock.mockReturnValue({ ...BASE, hasError: true });
    renderAnalytics();
    await waitFor(() =>
      expect(screen.getByText(/unable to load insights/i)).toBeInTheDocument(),
    );
    await expectBackLink();
  });

  it("in the loading state", async () => {
    analyticsMock.mockReturnValue({ ...BASE, isLoading: true });
    renderAnalytics();
    await expectBackLink();
  });
});
