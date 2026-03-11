import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { CoordinatorPage } from "@/pages/coordinator";

function renderCoordinator() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={["/manage/coordinator"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <CoordinatorPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("CoordinatorPage", () => {
  it("renders the page title", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("Coordinator Dashboard")).toBeInTheDocument();
    });
  });

  it("renders coordinator type card", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-type-card")).toBeInTheDocument();
    });
  });

  it("renders connection status card", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-connection-card")).toBeInTheDocument();
    });
  });

  it("renders tasks processed card", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-processed-card")).toBeInTheDocument();
    });
  });

  it("renders dead-lettered card", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-dead-letter-card")).toBeInTheDocument();
    });
  });

  it("shows in-memory coordinator type from mock data", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("In-Memory")).toBeInTheDocument();
    });
  });

  it("shows CONNECTED status from mock data", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("CONNECTED")).toBeInTheDocument();
    });
  });

  it("renders dead-letter table with entries", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("dead-letters-table")).toBeInTheDocument();
    });
  });

  it("shows dead-letter error messages", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByText("Connection timeout to external API")).toBeInTheDocument();
    });
  });

  it("renders purge all button", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("purge-dead-letters-btn")).toBeInTheDocument();
    });
  });

  it("renders replay and discard buttons for dead-letter entries", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("replay-1")).toBeInTheDocument();
      expect(screen.getByTestId("discard-1")).toBeInTheDocument();
    });
  });

  it("renders active queue depths", async () => {
    renderCoordinator();
    await waitFor(() => {
      expect(screen.getByTestId("coordinator-queues")).toBeInTheDocument();
    });
  });
});
