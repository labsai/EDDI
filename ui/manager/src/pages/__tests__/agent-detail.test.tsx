import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";

function renderAgentDetail(id = "agent1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={[`/manage/agentview/${id}`]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/agentview/:id" element={<AgentDetailPage />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("AgentDetailPage", () => {
  it("renders agent detail title", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByText("Agent Detail")).toBeInTheDocument();
    });
  });

  it("shows deployment status badge", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("deployment-status")).toBeInTheDocument();
    });
  });

  it("renders deploy button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("deploy-btn")).toBeInTheDocument();
    });
  });

  it("renders duplicate button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("duplicate-agent-btn")).toBeInTheDocument();
    });
  });

  it("renders export button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("export-agent-btn")).toBeInTheDocument();
    });
  });

  it("renders delete button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("delete-agent-btn")).toBeInTheDocument();
    });
  });

  it("renders environment badges section", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("env-badges")).toBeInTheDocument();
    });
  });

  it("renders add package button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("add-package-btn")).toBeInTheDocument();
    });
  });

  it("shows packages section with count", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByText("Workflows")).toBeInTheDocument();
    });
  });
});
