import { beforeEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CostDashboard } from "@/components/debugger/cost-dashboard";

function renderDashboard(conversationId: string | null = "conv-1") {
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

  it("renders component wrapper with data-testid", async () => {
    renderDashboard();
    await waitFor(() => {
      expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    });
  });

  it("renders empty state when conversationId is null", () => {
    renderDashboard(null);
    expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    expect(screen.getByText(/Send a message to see cost metrics/i)).toBeInTheDocument();
  });

  it("displays 'This Turn' section when audit data loads", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/This Turn/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays 'Conversation Total' section", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Conversation Total/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays dollar-sign cost values", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        const costElements = within(dashboard).getAllByText(/\$/);
        expect(costElements.length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  it("displays 'Tool Usage' section", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Tool Usage/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays 'Tool Calls' count in Conversation Total", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Tool Calls/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays token metrics labels", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        const tokenLabels = within(dashboard).getAllByText(/Tokens/i);
        expect(tokenLabels.length).toBeGreaterThan(0);
      },
      { timeout: 5000 },
    );
  });

  it("displays 'Duration' in This Turn section", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Duration/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });

  it("displays 'Cache' section with hit rate", async () => {
    renderDashboard("conv-1");
    await waitFor(
      () => {
        const dashboard = screen.getByTestId("cost-dashboard");
        expect(within(dashboard).getByText(/Cache/)).toBeInTheDocument();
        expect(within(dashboard).getByText(/Hit Rate/i)).toBeInTheDocument();
      },
      { timeout: 5000 },
    );
  });
});
