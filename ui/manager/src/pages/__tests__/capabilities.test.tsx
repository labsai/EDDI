import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CapabilitiesPage } from "@/pages/capabilities";
import userEvent from "@testing-library/user-event";

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-capabilities">
          <CapabilitiesPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("CapabilitiesPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("capabilities-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText(/Capability (Registry|Discovery)/i)).toBeInTheDocument();
  });

  it("renders the search input", () => {
    renderPage();
    expect(screen.getByTestId("capability-search")).toBeInTheDocument();
  });

  it("renders the strategy selector", () => {
    renderPage();
    expect(screen.getByTestId("capability-strategy")).toBeInTheDocument();
  });

  it("shows skills grid after loading", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("skills-grid")).toBeInTheDocument();
    });
  });

  it("renders skill buttons from mock data", async () => {
    renderPage();
    await waitFor(() => {
      // These skill names come from the existing ALL_SKILLS mock in handlers.ts
      expect(screen.getByTestId("skill-customer-support")).toBeInTheDocument();
      expect(screen.getByTestId("skill-faq")).toBeInTheDocument();
      expect(screen.getByTestId("skill-contract-analysis")).toBeInTheDocument();
    });
  });

  it("shows the registry overview table", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("registry-table")).toBeInTheDocument();
    });
  });

  it("renders registry rows for each skill", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("registry-row-customer-support")).toBeInTheDocument();
      expect(screen.getByTestId("registry-row-faq")).toBeInTheDocument();
      expect(screen.getByTestId("registry-row-contract-analysis")).toBeInTheDocument();
    });
  });

  it("expands a registry row to show agents", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("registry-row-customer-support")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("registry-row-customer-support"));

    await waitFor(() => {
      expect(screen.getByTestId("registry-expanded-customer-support")).toBeInTheDocument();
    });
  });

  it("shows matching agents when a skill pill is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("skill-customer-support")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("skill-customer-support"));

    await waitFor(() => {
      expect(screen.getByTestId("capability-results")).toBeInTheDocument();
      expect(screen.getByText("Matching Agents")).toBeInTheDocument();
    });
  });
});
