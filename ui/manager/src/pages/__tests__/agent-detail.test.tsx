import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

/** Wait for section to load, then expand the A2A collapsible header */
async function expandA2ASection() {
  const section = await screen.findByTestId("a2a-section");
  const header = section.querySelector("button");
  if (header) {
    await userEvent.click(header);
  }
}

describe("AgentDetailPage", () => {
  it("renders agent detail title", async () => {
    renderAgentDetail();
    await waitFor(() => {
      // MSW returns descriptor name "Support Agent" for agent1
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
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
    }, { timeout: 5000 });
  });

  it("renders add package button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("add-workflow-btn")).toBeInTheDocument();
    });
  });

  it("shows packages section with count", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByText("Workflows")).toBeInTheDocument();
    });
  });

  it("renders A2A protocol section", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("a2a-section")).toBeInTheDocument();
    });
  });

  it("shows A2A enabled state with description and skills", async () => {
    renderAgentDetail();
    // A2A is now collapsed by default — expand it first
    await expandA2ASection();
    await waitFor(() => {
      expect(screen.getByTestId("a2a-description")).toBeInTheDocument();
      expect(screen.getByTestId("a2a-skill-input")).toBeInTheDocument();
    });
  });

  it("shows A2A endpoint URLs when enabled", async () => {
    renderAgentDetail();
    await expandA2ASection();
    await waitFor(() => {
      // Should show both GET and POST endpoint badges
      expect(screen.getByText("GET")).toBeInTheDocument();
      expect(screen.getByText("POST")).toBeInTheDocument();
    });
  });

  it("shows Agent Card Preview toggle when A2A is enabled", async () => {
    renderAgentDetail();
    await expandA2ASection();
    await waitFor(() => {
      expect(screen.getByTestId("a2a-card-toggle")).toBeInTheDocument();
    });
  });

  it("shows A2A skills from mock data", async () => {
    renderAgentDetail();
    await expandA2ASection();
    await waitFor(() => {
      expect(screen.getByText("order-tracking")).toBeInTheDocument();
      expect(screen.getByText("return-processing")).toBeInTheDocument();
    });
  });
});
