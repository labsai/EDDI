import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";

import { QuotasPage } from "@/pages/quotas";

function renderQuotas() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <MemoryRouter initialEntries={["/manage/quotas"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <QuotasPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("QuotasPage", () => {
  it("renders the page header", async () => {
    renderQuotas();
    expect(screen.getByText("Tenant Quotas")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Configure rate limits, usage caps, and cost budgets per tenant.",
      ),
    ).toBeInTheDocument();
  });

  it("renders the quotas-page container", () => {
    renderQuotas();
    expect(screen.getByTestId("quotas-page")).toBeInTheDocument();
  });

  it("renders save button", () => {
    renderQuotas();
    expect(screen.getByTestId("quotas-save")).toBeInTheDocument();
  });

  it("loads and displays quota configuration fields", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quota-max-conversations")).toBeInTheDocument();
    });
    expect(screen.getByTestId("quota-max-agents")).toBeInTheDocument();
    expect(screen.getByTestId("quota-max-api-calls")).toBeInTheDocument();
    expect(screen.getByTestId("quota-max-cost")).toBeInTheDocument();
  });

  it("loads and displays usage data", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("usage-conversations")).toBeInTheDocument();
    });
    expect(screen.getByTestId("usage-api-calls")).toBeInTheDocument();
    expect(screen.getByTestId("usage-cost")).toBeInTheDocument();
    expect(screen.getByTestId("usage-tenant-id")).toBeInTheDocument();
  });

  it("shows the toggle enabled button", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quotas-toggle-enabled")).toBeInTheDocument();
    });
    expect(screen.getByText("Enabled")).toBeInTheDocument();
  });

  it("shows reset usage button", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("quotas-reset-usage")).toBeInTheDocument();
    });
    expect(screen.getByText("Reset Counters")).toBeInTheDocument();
  });

  it("displays tenant ID in usage card", async () => {
    renderQuotas();
    await waitFor(() => {
      expect(screen.getByTestId("usage-tenant-id")).toHaveTextContent("default");
    });
  });
});
