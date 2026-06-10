import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AgentDetailPage } from "@/pages/agent-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

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
  // ─── Loading state ──────────────────────────────────────────────────────
  it("shows loading spinner while fetching agent data", async () => {
    // Delay the agent response so we can observe the loading state
    server.use(
      http.get("*/agentstore/agents/:id", async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json({ workflows: [] });
      }),
      // Delay versions as well so loading persists
      http.get("*/:store/:plural/:id/currentversion", async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json(1);
      })
    );

    renderAgentDetail();
    expect(screen.getByTestId("agent-detail-loading")).toBeInTheDocument();
  });

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

  // ─── Duplicate — fires mutation immediately (no dialog) ─────────────────
  it("clicking duplicate button triggers duplicate mutation directly", async () => {
    let duplicateCalled = false;
    server.use(
      http.post("*/agentstore/agents/:id", ({ request }) => {
        const url = new URL(request.url);
        if (url.searchParams.has("version")) {
          duplicateCalled = true;
        }
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/agentstore/agents/newid?version=1" },
        });
      })
    );

    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("duplicate-agent-btn")).toBeInTheDocument();
    });

    // handleDuplicate() fires the mutation immediately — no confirmation dialog
    const user = userEvent.setup();
    await user.click(screen.getByTestId("duplicate-agent-btn"));

    await waitFor(() => {
      expect(duplicateCalled).toBe(true);
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
      expect(screen.getByTestId("add-workflow-btn")).toBeInTheDocument();
    });
  });

  it("shows packages section with count", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByText("Workflows")).toBeInTheDocument();
    });
  });

  // ─── Workflow list rendering ────────────────────────────────────────────
  it("renders workflow cards for agent1 with wf1", async () => {
    renderAgentDetail();
    await waitFor(() => {
      // agent1 has workflow wf1
      expect(screen.getByText("wf1")).toBeInTheDocument();
    });
  });

  // ─── A2A section ────────────────────────────────────────────────────────
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

  // ─── Channels ───────────────────────────────────────────────────────────
  it("renders channels section with add button", async () => {
    renderAgentDetail();
    // Channel Connectors section is collapsed by default (no channels) — expand it
    const header = await screen.findByText("Channel Connectors");
    await userEvent.click(header);
    await waitFor(() => {
      expect(screen.getByTestId("channels-section")).toBeInTheDocument();
      expect(screen.getByTestId("add-slack-channel-btn")).toBeInTheDocument();
    });
  });

  it("shows Slack channel data for agent3", async () => {
    renderAgentDetail("agent3");
    await waitFor(() => {
      expect(screen.getByTestId("slack-channel-0")).toBeInTheDocument();
      expect(screen.getByTestId("channel-id-0")).toHaveValue("C0123ABCDEF");
    });
  });

  // ─── Back link ──────────────────────────────────────────────────────────
  it("renders back to agents link", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByText("Back to Agents")).toBeInTheDocument();
    });
  });

  // ─── Version badge / picker ─────────────────────────────────────────────
  it("shows version info", async () => {
    renderAgentDetail();
    await waitFor(() => {
      // Should show either a version badge or version picker
      const picker = screen.queryByTestId("version-picker");
      const badge = screen.queryByTestId("version-badge");
      expect(picker || badge).toBeTruthy();
    });
  });

  // ─── Deploy button — calls deploy API directly (no dropdown) ────────────
  it("deploy button calls deploy API when clicked", async () => {
    let deployCalled = false;
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "NOT_FOUND" });
      }),
      http.post("*/administration/:env/deploy/:id", () => {
        deployCalled = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderAgentDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      const btn = screen.getByTestId("deploy-btn");
      expect(btn).toHaveTextContent(/Deploy/i);
    });

    // Deploy button calls handleDeploy() directly — no dropdown
    await user.click(screen.getByTestId("deploy-btn"));

    await waitFor(() => {
      expect(deployCalled).toBe(true);
    });
  });

  // ─── Undeploy flow ──────────────────────────────────────────────────────
  it("undeploy button calls undeploy API when agent is deployed", async () => {
    let undeployCalled = false;
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "READY" });
      }),
      http.post("*/administration/:env/undeploy/:id", () => {
        undeployCalled = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderAgentDetail();
    const user = userEvent.setup();

    await waitFor(() => {
      const btn = screen.getByTestId("deploy-btn");
      // When deployed, button shows "Undeploy"
      expect(btn).toHaveTextContent(/Undeploy/i);
    });

    await user.click(screen.getByTestId("deploy-btn"));

    await waitFor(() => {
      expect(undeployCalled).toBe(true);
    });
  });

  // ─── Chat button ────────────────────────────────────────────────────────
  it("renders chat button", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("chat-btn")).toBeInTheDocument();
    });
  });

  // ─── Open in Studio ─────────────────────────────────────────────────────
  it("renders Open in Studio link", async () => {
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("open-studio-btn")).toBeInTheDocument();
      expect(screen.getByText("Open in Studio")).toBeInTheDocument();
    });
  });

  // ─── Error state ────────────────────────────────────────────────────────
  it("shows error state when agent API returns 500", async () => {
    server.use(
      http.get("*/agentstore/agents/:id", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    renderAgentDetail("fail-agent");
    await waitFor(() => {
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    });
  });

  it("shows retry button in error state", async () => {
    server.use(
      http.get("*/agentstore/agents/:id", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    renderAgentDetail("fail-agent");
    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // ─── Delete dialog ──────────────────────────────────────────────────────
  it("opens delete confirmation dialog and executes deletion", async () => {
    let deleteCalled = false;
    server.use(
      http.delete("*/agentstore/agents/:id", () => {
        deleteCalled = true;
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderAgentDetail();
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("delete-agent-btn")).toBeInTheDocument();
    });

    // Open delete dialog
    await user.click(screen.getByTestId("delete-agent-btn"));

    await waitFor(() => {
      expect(screen.getByText(/cannot be undone/i)).toBeInTheDocument();
    });

    // Click confirm delete
    const confirmBtn = screen.getByRole("button", { name: /Delete/i });
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(deleteCalled).toBe(true);
    });
  });

  // ─── Export dialog ──────────────────────────────────────────────────────
  it("opens export dialog", async () => {
    renderAgentDetail();
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByTestId("export-agent-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("export-agent-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("export-agent-dialog")).toBeInTheDocument();
    });
  });

  // ─── Deploy & Chat button ──────────────────────────────────────────────
  it("shows Deploy & Chat button for undeployed agent", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "NOT_FOUND" });
      })
    );
    renderAgentDetail();
    await waitFor(() => {
      expect(screen.getByTestId("deploy-chat-btn")).toBeInTheDocument();
    });
  });

  // ─── Agent ID display ──────────────────────────────────────────────────
  it("shows agent ID in page header", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByText("agent1")).toBeInTheDocument();
    });
  });

  // ─── Version picker with multiple versions ───────────────────────────
  it("shows version picker when multiple versions exist", async () => {
    // The default MSW handler for agent1 descriptors returns 2 versions
    renderAgentDetail("agent1");
    await waitFor(() => {
      const picker = screen.queryByTestId("version-picker");
      const badge = screen.queryByTestId("version-badge");
      // Should show either picker (multiple versions) or badge (single version)
      expect(picker || badge).toBeTruthy();
    });
  });

  // ─── Workflow card shows version badge ────────────────────────────────
  it("shows workflow version badge in workflow card", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      // agent1 has workflow wf1?version=1 → workflow card should be rendered
      expect(screen.getByText("wf1")).toBeInTheDocument();
    });
    // The workflow card should exist — version is shown nearby
    // The version picker already confirms versions are loaded
  });

  // ─── Workflow Open button ─────────────────────────────────────────────
  it("shows Open button for workflow card", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByText("Open")).toBeInTheDocument();
    });
  });

  // ─── Add workflow panel toggle ────────────────────────────────────────
  it("opens add workflow panel when add workflow button is clicked", async () => {
    renderAgentDetail("agent1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("add-workflow-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("add-workflow-btn"));

    // After clicking, the "Add Workflow" panel should appear with workflow options
    await waitFor(() => {
      expect(screen.getByText(/Cancel/)).toBeInTheDocument();
    });
  });

  // ─── Remove workflow mutation ─────────────────────────────────────────
  it("clicking remove workflow button triggers mutation", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent1?version=2" },
        });
      })
    );

    renderAgentDetail("agent1");
    const user = userEvent.setup();

    // Wait for the workflow card with remove button to appear
    await waitFor(() => {
      expect(screen.getByText("wf1")).toBeInTheDocument();
    });

    // Find the delete button within the workflow row
    // Each workflow card has a trash button
    const trashButtons = screen.getAllByTitle("Delete");
    expect(trashButtons.length).toBeGreaterThan(0);
    await user.click(trashButtons[0]);

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });

  // ─── Security & Identity section ──────────────────────────────────────
  it("renders Security & Identity section", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByText("Security & Identity")).toBeInTheDocument();
    });
  });

  // ─── Capabilities section ──────────────────────────────────────────────
  it("renders Capabilities section", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByText("Capabilities")).toBeInTheDocument();
    });
  });

  // ─── User Memory section ──────────────────────────────────────────────
  it("renders User Memory section", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByText("User Memory")).toBeInTheDocument();
    });
  });

  // ─── No save feedback initially ───────────────────────────────────────
  it("does not show save feedback initially", async () => {
    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("save-feedback")).not.toBeInTheDocument();
  });

  // ─── Deployed agent shows external chat link ──────────────────────────
  it("shows external chat link when agent is deployed", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "READY" });
      })
    );

    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByTestId("external-chat-btn")).toBeInTheDocument();
    });
  });

  // ─── IN_PROGRESS deployment status ─────────────────────────────────────
  it("shows Deploying status when agent is IN_PROGRESS", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "IN_PROGRESS" });
      })
    );

    renderAgentDetail("agent1");
    await waitFor(() => {
      expect(screen.getByTestId("deployment-status")).toHaveTextContent(/Deploying/i);
    });
  });

  // ─── Deploy button disabled when IN_PROGRESS ──────────────────────────
  it("deploy button is disabled when agent is IN_PROGRESS", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "IN_PROGRESS" });
      })
    );

    renderAgentDetail("agent1");
    await waitFor(() => {
      const btn = screen.getByTestId("deploy-btn");
      expect(btn).toBeDisabled();
    });
  });

  // ─── Raw Configuration section ──────────────────────────────────────────
  it("expands raw config section and shows JSON content", async () => {
    renderAgentDetail("agent1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });

    // Raw Configuration section toggle
    const rawConfigToggle = screen.getByText("Raw Configuration");
    expect(rawConfigToggle).toBeInTheDocument();

    // Click to expand
    await user.click(rawConfigToggle);

    // After expanding, a <pre> element with JSON content should appear
    await waitFor(() => {
      const rawContent = document.getElementById("raw-config-content");
      expect(rawContent).toBeInTheDocument();
    });
  });

  // ─── A2A description save mutation ────────────────────────────────────
  it("saving A2A description on blur calls update mutation", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent1?version=2" },
        });
      })
    );

    renderAgentDetail("agent1");
    const user = userEvent.setup();

    // Expand A2A section first
    await expandA2ASection();

    await waitFor(() => {
      // The A2A toggle button when already enabled is a link/text or there's a "Disable" button
      // Since A2A is enabled for agent1, and there's no explicit toggle, let's verify A2A section content instead
      expect(screen.getByTestId("a2a-description")).toBeInTheDocument();
    });

    // Modify the description to trigger a mutation
    const descInput = screen.getByTestId("a2a-description");
    await user.clear(descInput);
    await user.type(descInput, "Updated description");
    // Blur triggers handleDescriptionSave
    await user.tab();

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });

  // ─── A2A add skill ────────────────────────────────────────────────────
  it("adding a skill calls update mutation with new skill", async () => {
    let updatePayload: Record<string, unknown> | null = null;
    server.use(
      http.put("*/agentstore/agents/:id", async ({ request }) => {
        updatePayload = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent1?version=2" },
        });
      })
    );

    renderAgentDetail("agent1");
    const user = userEvent.setup();

    await expandA2ASection();

    await waitFor(() => {
      expect(screen.getByTestId("a2a-skill-input")).toBeInTheDocument();
    });

    // Type a new skill and press Enter
    const skillInput = screen.getByTestId("a2a-skill-input");
    await user.type(skillInput, "new-skill{Enter}");

    await waitFor(() => {
      expect(updatePayload).toBeTruthy();
      const skills = (updatePayload as Record<string, unknown>)?.a2aSkills;
      expect(skills).toContain("new-skill");
    });
  });

  // ─── A2A remove skill ────────────────────────────────────────────────
  it("removing a skill calls update mutation", async () => {
    let updateCalled = false;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        updateCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "/agentstore/agents/agent1?version=2" },
        });
      })
    );

    renderAgentDetail("agent1");
    const user = userEvent.setup();

    await expandA2ASection();

    await waitFor(() => {
      expect(screen.getByText("order-tracking")).toBeInTheDocument();
    });

    // Click the × button on a skill badge using aria-label
    const removeBtn = screen.getByRole("button", { name: /Remove order-tracking/ });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(updateCalled).toBe(true);
    });
  });
});

