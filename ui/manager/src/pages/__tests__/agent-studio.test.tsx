import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";
import { AgentStudioPage } from "@/pages/agent-studio";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderStudio(agentId = "agent1") {
  return renderPage(
    `/manage/studio/${agentId}`,
    <AgentStudioPage />,
    "/manage/studio/:agentId"
  );
}

describe("Agent Studio Page", () => {
  it("renders the studio layout with data-testid", async () => {
    renderStudio();
    await waitFor(() => {
      expect(screen.getByTestId("agent-studio")).toBeInTheDocument();
    });
  });

  it("shows actual agent name from mock data in the header", async () => {
    renderStudio();
    // The agent descriptor resolves agent1 to "Support Agent"
    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });
    // The subtitle should say "Agent Studio"
    expect(screen.getByText("Agent Studio")).toBeInTheDocument();
  });

  it("renders the back link to agent detail", async () => {
    renderStudio();
    await waitFor(() => {
      const backLink = screen.getByTestId("studio-back");
      expect(backLink).toBeInTheDocument();
      expect(backLink).toHaveAttribute("href", "/manage/agentview/agent1");
    });
  });

  it("renders the back link with aria-label", async () => {
    renderStudio();
    await waitFor(() => {
      const backLink = screen.getByTestId("studio-back");
      expect(backLink).toHaveAttribute("aria-label");
    });
  });

  it("has pipeline label in mobile tab bar", async () => {
    renderStudio();
    await waitFor(() => {
      // The mobile tab bar is always rendered (shown via lg:hidden)
      const tab = screen.getByTestId("mobile-tab-pipeline");
      expect(tab).toBeInTheDocument();
      expect(tab).toHaveTextContent("Pipeline");
    });
  });

  it("renders the toggle chat button", async () => {
    renderStudio();
    await waitFor(() => {
      const toggleBtn = screen.getByTestId("toggle-right-panel");
      expect(toggleBtn).toBeInTheDocument();
    });
  });

  it("shows the empty editor placeholder when no stage is selected", async () => {
    renderStudio();
    await waitFor(() => {
      expect(
        screen.getByText(/Click a pipeline stage/i)
      ).toBeInTheDocument();
    });
  });

  it("renders mobile tab bar with pipeline, editor, chat tabs", async () => {
    renderStudio();
    await waitFor(() => {
      expect(screen.getByTestId("mobile-tab-pipeline")).toBeInTheDocument();
      expect(screen.getByTestId("mobile-tab-editor")).toBeInTheDocument();
      expect(screen.getByTestId("mobile-tab-chat")).toBeInTheDocument();
    });
  });

  it("loads pipeline stages from the workflow and shows stage content", async () => {
    renderStudio();
    // The mock workflow for agent1 has 5 steps: parser, rules, property, llm, output
    // The pipeline railroad renders them with data-testid="stage-{idx}"
    // Note: PipelineRailroad renders twice (desktop + mobile), so use getAllByTestId
    await waitFor(
      () => {
        const railroads = screen.getAllByTestId("pipeline-railroad");
        expect(railroads.length).toBeGreaterThanOrEqual(1);
        // Verify actual pipeline stage labels from mock data
        const stages = screen.getAllByTestId("stage-0");
        expect(stages.length).toBeGreaterThanOrEqual(1);
        const lastStages = screen.getAllByTestId("stage-4");
        expect(lastStages.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 3000 }
    );

    // Verify actual extension type labels are rendered
    await waitFor(() => {
      expect(screen.getAllByText("Rules").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("LLM").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("Output").length).toBeGreaterThanOrEqual(1);
    });
  });

  it("keeps the mobile editor (and its unsaved edit) mounted when switching tabs", async () => {
    // Below lg the editor used to render only while its tab was active, so
    // tapping Pipeline or Chat unmounted a dirty editor and lost the edit.
    renderStudio();
    const user = userEvent.setup();
    const stages = await screen.findAllByTestId("stage-1", undefined, { timeout: 3000 });
    await user.click(stages[0]!);

    // Selecting a stage switches the mobile view to the editor tab.
    await waitFor(() => {
      expect(screen.getByTestId("studio-mobile-editor")).not.toHaveClass("hidden");
    });
    const editorContainer = screen.getByTestId("studio-mobile-editor");
    const panel = editorContainer.firstElementChild;
    expect(panel).not.toBeNull();

    await user.click(screen.getByTestId("mobile-tab-pipeline"));
    expect(screen.getByTestId("studio-mobile-editor")).toHaveClass("hidden");
    // The very same element — not remounted.
    expect(screen.getByTestId("studio-mobile-editor")).toBe(editorContainer);
    expect(editorContainer.firstElementChild).toBe(panel);
    expect(panel!.isConnected).toBe(true);

    await user.click(screen.getByTestId("mobile-tab-editor"));
    expect(screen.getByTestId("studio-mobile-editor")).not.toHaveClass("hidden");
  });

  it("can select a pipeline stage by clicking", async () => {
    renderStudio();
    const user = userEvent.setup();

    await waitFor(
      () => {
        // Multiple renders of stage-1 exist (desktop + mobile)
        const stages = screen.getAllByTestId("stage-1");
        expect(stages.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 3000 }
    );

    // Click on the first "Rules" stage (index 1) - pick first one (desktop)
    const stageButtons = screen.getAllByTestId("stage-1");
    await user.click(stageButtons[0]!);

    // After selecting, the button should have aria-current="true"
    await waitFor(() => {
      const updatedStages = screen.getAllByTestId("stage-1");
      const hasAriaCurrent = updatedStages.some(
        (el) => el.getAttribute("aria-current") === "true"
      );
      expect(hasAriaCurrent).toBe(true);
    });
  });

  // ─── Prerequisite query failures ────────────────────────────────────────

  // Each studio query is gated on the previous one succeeding, so a failed
  // prerequisite leaves the next query *disabled* rather than errored — it
  // never reports isLoading or isError. These pin that every failure still
  // reaches an error state instead of the chrome with an empty pipeline.

  it("shows an error state when the agent descriptor fetch fails", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json({ message: "boom" }, { status: 500 });
      })
    );

    renderStudio();

    await waitFor(() => {
      expect(screen.getByTestId("error-state")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("agent-studio")).not.toBeInTheDocument();
  });

  it("shows an error state when the workflow fetch fails", async () => {
    server.use(
      http.get("*/workflowstore/workflows/:id", () => {
        return HttpResponse.json({ message: "boom" }, { status: 500 });
      })
    );

    renderStudio();

    await waitFor(() => {
      expect(screen.getByTestId("error-state")).toBeInTheDocument();
    });
    // An empty pipeline would otherwise read as "this agent has no workflow".
    expect(screen.queryByTestId("agent-studio")).not.toBeInTheDocument();
  });
});
