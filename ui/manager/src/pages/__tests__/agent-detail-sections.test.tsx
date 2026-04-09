import { describe, it, expect } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";

function renderPage(agentId = "agent1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/agentview/${agentId}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-sections">
          <Routes>
            <Route
              path="/manage/agentview/:id"
              element={<AgentDetailPage />}
            />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("Agent Detail — Config Sections (Phase 15.4)", () => {
  it("renders the agent detail page with agent name", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });
  });

  it("renders Security & Identity section header", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
    });
  });

  it("renders Capabilities section header", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Capabilities/i)).toBeInTheDocument();
    });
  });

  it("renders User Memory section header", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/User Memory/i)).toBeInTheDocument();
    });
  });

  it("renders security toggles when section is expanded", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
    });

    // Click to expand the Security & Identity section
    fireEvent.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
    });
  });

  it("shows all three security toggle descriptions", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
      expect(screen.getByText(/Sign MCP invocations/i)).toBeInTheDocument();
      expect(screen.getByText(/Require peer verification/i)).toBeInTheDocument();
    });
  });

  it("shows identity fields when security section is expanded", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Security & Identity/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText(/Security & Identity/i));

    await waitFor(() => {
      expect(screen.getByTestId("identity-section")).toBeInTheDocument();
    });
  });

  it("renders capabilities section content when expanded", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Capabilities/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText(/Capabilities/i));

    await waitFor(() => {
      expect(screen.getByTestId("capabilities-section")).toBeInTheDocument();
    });
  });

  it("renders user memory section content when expanded", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/User Memory/i)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText(/User Memory/i));

    await waitFor(() => {
      expect(screen.getByTestId("user-memory-section")).toBeInTheDocument();
      expect(screen.getByText(/Enable Memory Tools/i)).toBeInTheDocument();
    });
  });
});
