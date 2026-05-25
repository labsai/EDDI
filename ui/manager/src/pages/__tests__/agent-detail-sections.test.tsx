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

  // Security section auto-opens because agent.identity.agentDid is set in mock data
  it("renders security toggles (section auto-opens)", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
    });
  });

  it("shows all three security toggle descriptions", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Sign inter-agent/i)).toBeInTheDocument();
      expect(screen.getByText(/Sign MCP invocations/i)).toBeInTheDocument();
      expect(screen.getByText(/Require peer verification/i)).toBeInTheDocument();
    });
  });

  it("shows identity fields (section auto-opens)", async () => {
    renderPage();
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

  // ─── Agentic Improvements ─────────────────────────────────────────

  it("renders Session Management section header", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Session Management/i)).toBeInTheDocument();
    });
  });

  it("renders auto-snapshot toggle from mock data (enabled)", async () => {
    renderPage();
    // Session Management auto-opens because autoSnapshot.enabled is true
    await waitFor(() => {
      expect(screen.getByTestId("auto-snapshot-enabled")).toBeInTheDocument();
    });
    const toggle = screen.getByTestId("auto-snapshot-enabled") as HTMLInputElement;
    expect(toggle.checked).toBe(true);
  });

  it("renders forking toggle as disabled (coming soon)", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("forking-enabled")).toBeInTheDocument();
    });
    const toggle = screen.getByTestId("forking-enabled") as HTMLInputElement;
    expect(toggle.disabled).toBe(true);
  });

  it("shows identity section with agentDid from mock data", async () => {
    renderPage();
    await waitFor(() => {
      // Mock data has identity.agentDid = "did:eddi:agent-1"
      expect(screen.getByDisplayValue("did:eddi:agent-1")).toBeInTheDocument();
    });
  });
});
