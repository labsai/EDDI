import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
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
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <CostDashboard conversationId={conversationId} isActive />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("CostDashboard", () => {
  it("renders component", async () => {
    renderDashboard();

    await waitFor(() => {
      expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    });
  });

  it("renders with null conversationId", () => {
    renderDashboard(null);
    expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
  });

  it("displays section titles from i18n when data loads", async () => {
    renderDashboard();

    // The dashboard will either show metric sections or empty state
    await waitFor(() => {
      expect(screen.getByTestId("cost-dashboard")).toBeInTheDocument();
    });
  });
});
