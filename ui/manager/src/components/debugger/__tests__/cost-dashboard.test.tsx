import { beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CostDashboard } from "@/components/debugger/cost-dashboard";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderDashboard(conversationId: string | null = "conv1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-cost">
          <CostDashboard conversationId={conversationId} isActive />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("CostDashboard", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  // ── Empty / null states ────────────────────────────────────────────
  it("renders empty state when conversationId is null", () => {
    renderDashboard(null);
    expect(screen.getByText(/Send a message to see cost metrics/i)).toBeInTheDocument();
  });

  it("renders empty state when conversationId is empty string", () => {
    renderDashboard("");
    expect(screen.getByText(/Send a message to see cost metrics/i)).toBeInTheDocument();
  });

  // ── Stat cards ─────────────────────────────────────────────────────
  it("renders cost-dashboard container with data-testid", async () => {
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    });
  });

  it("displays Total Cost stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Total Cost/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays Total Tokens stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Total Tokens/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays Turns stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Turns/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays Avg Latency stat card", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Avg Latency/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  // ── Token distribution ─────────────────────────────────────────────
  it("displays token distribution bar with input/output", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Input/i)).toBeInTheDocument();
        expect(within(dashboard).getByText(/Output/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  // ── Dollar cost values ─────────────────────────────────────────────
  it("displays dollar-sign cost values", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        const costElements = within(dashboard).getAllByText(/\$/);
        expect(costElements.length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  // ── Per-turn table ─────────────────────────────────────────────────
  it("displays per-turn table with model name", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getAllByText(/5\.4-mini/i).length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  it("displays token counts in per-turn table", async () => {
    renderDashboard();
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        const tokenLabels = within(dashboard).getAllByText(/Tokens/i);
        expect(tokenLabels.length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  // ── Error state ────────────────────────────────────────────────────
  it("shows error state when both APIs fail", async () => {
    server.use(
      http.get("*/llm/tools/costs/conversation/*", () => {
        return new HttpResponse(null, { status: 500 });
      }),
      http.get("*/auditstore/*", () => {
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderDashboard("conv-err");
    await waitFor(
      () => {
        expect(screen.getByTestId("cost-dashboard-error")).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });
});
