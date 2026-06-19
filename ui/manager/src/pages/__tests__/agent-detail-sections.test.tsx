import { afterEach, describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

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
  afterEach(() => {
    server.resetHandlers();
  });

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
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Capabilities/i)).toBeInTheDocument();
    });

    await user.click(screen.getByText(/Capabilities/i));

    await waitFor(() => {
      expect(screen.getByTestId("capabilities-section")).toBeInTheDocument();
    });
  });

  it("renders user memory section content when expanded", async () => {
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/User Memory/i)).toBeInTheDocument();
    });

    await user.click(screen.getByText(/User Memory/i));

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

  // ─── A2A Section Tests ─────────────────────────────────────────

  it("tests full A2A section features", async () => {
    const user = userEvent.setup();
    // Setup MSW to return an agent with A2A enabled
    server.use(
      http.get("*/agentstore/agents/:id", () => {
        return HttpResponse.json({
          a2aEnabled: true,
          a2aSkills: ["translation"],
          description: "My A2A Agent",
          identity: {},
        });
      })
    );

    renderPage("agent-a2a");

    await waitFor(() => {
      expect(screen.getByText(/Agent-to-Agent \(A2A\)/i)).toBeInTheDocument();
    });

    // Expand the section by clicking the button directly
    const a2aBtn = screen.getByRole("button", { name: /Agent-to-Agent/i });
    await user.click(a2aBtn);

    // The section should show Agent Description input because A2A is enabled
    await waitFor(() => {
      expect(screen.getByTestId("a2a-description")).toBeInTheDocument();
    });

    // Check skills
    expect(screen.getByText("translation")).toBeInTheDocument();

    // Toggle Agent Card Preview
    const previewToggle = screen.getByTestId("a2a-card-toggle");
    await user.click(previewToggle);

    await waitFor(() => {
      // The preview should render the JSON containing "translation"
      expect(screen.getByText(/"name": "translation"/)).toBeInTheDocument();
    });

    // Copy URL button should be present — use data-testid for reliable selection
    const rpcCopyBtn = screen.getByTestId("copy-url-rpc");
    expect(rpcCopyBtn).toBeInTheDocument();
  });

  // ─── Mutation verification: A2A toggle calls update API ─────────────────
  it("toggling A2A enable calls the update agent API", async () => {
    const user = userEvent.setup();
    let updateCalled = false;
    server.use(
      http.get("*/agentstore/agents/:id", () => {
        return HttpResponse.json({
          a2aEnabled: false,
          a2aSkills: [],
          description: "Test agent",
          identity: {},
        });
      }),
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent-a2a?version=2" },
        });
      })
    );

    renderPage("agent-a2a-toggle");

    // Expand A2A section
    await waitFor(() => {
      expect(screen.getByText(/Agent-to-Agent \(A2A\)/i)).toBeInTheDocument();
    });
    const a2aBtn = screen.getByRole("button", { name: /Agent-to-Agent/i });
    await user.click(a2aBtn);

    // When A2A is disabled, an "Enable A2A" button appears
    await waitFor(() => {
      expect(screen.getByTestId("enable-a2a-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("enable-a2a-btn"));

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });
});
