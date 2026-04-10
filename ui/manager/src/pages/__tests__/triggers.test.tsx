import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { TriggersPage } from "@/pages/triggers";
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
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-triggers">
          <TriggersPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("TriggersPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("triggers-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText("Agent Triggers")).toBeInTheDocument();
  });

  it("renders the create button", () => {
    renderPage();
    expect(screen.getByTestId("create-trigger-btn")).toBeInTheDocument();
  });

  it("shows trigger list with mock data", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("trigger-list")).toBeInTheDocument();
      expect(screen.getByTestId("trigger-booking_request")).toBeInTheDocument();
      expect(screen.getByTestId("trigger-faq_query")).toBeInTheDocument();
      expect(screen.getByTestId("trigger-escalation")).toBeInTheDocument();
    });
  });

  it("shows agent count per trigger", async () => {
    renderPage();
    await waitFor(() => {
      // booking_request (2) and escalation (2) both show "2 agents"
      const twoCounts = screen.getAllByText("2 agents");
      expect(twoCounts.length).toBeGreaterThanOrEqual(1);
      // faq_query shows "1 agents"
      expect(screen.getByText("1 agents")).toBeInTheDocument();
    });
  });

  it("opens create dialog when create button is clicked", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-dialog")).toBeInTheDocument();
      expect(screen.getByText("Create Trigger")).toBeInTheDocument();
    });
  });

  it("shows intent input and deployments in dialog", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-intent-input")).toBeInTheDocument();
      expect(screen.getByText("Agent Deployments")).toBeInTheDocument();
    });
  });

  it("shows search input when triggers exist", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("trigger-search")).toBeInTheDocument();
    });
  });

  it("filters triggers by search text", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-list")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("trigger-search"), "faq");

    await waitFor(() => {
      expect(screen.getByTestId("trigger-faq_query")).toBeInTheDocument();
      expect(screen.queryByTestId("trigger-booking_request")).not.toBeInTheDocument();
    });
  });

  it("closes dialog on Escape key", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-dialog")).toBeInTheDocument();
    });

    await user.keyboard("{Escape}");

    await waitFor(() => {
      expect(screen.queryByTestId("trigger-dialog")).not.toBeInTheDocument();
    });
  });
});
