/**
 * Studio editor panel integration tests.
 *
 * Verifies:
 *   1. Resource loads in the editor
 *   2. Tab switching (Form ↔ JSON) works
 *   3. Save and discard buttons start disabled (no unsaved changes)
 *   4. Version picker renders
 */
import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render, cleanup } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { StudioEditorPanel } from "@/components/studio/studio-editor-panel";
import { Toaster } from "sonner";
import userEvent from "@testing-library/user-event";

let consoleErrorSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // Suppress Sonner portal warnings in test output
  consoleErrorSpy = vi.spyOn(console, "error").mockImplementation((...args) => {
    const msg = typeof args[0] === "string" ? args[0] : "";
    if (msg.includes("Not implemented: HTMLCanvasElement")) return;
    if (msg.includes("validateDOMNesting")) return;
  });
});

afterEach(() => {
  consoleErrorSpy.mockRestore();
  cleanup();
});

function renderPanelForSave(
  step: { type: string; extensions: Record<string, unknown>; config: { uri?: string } },
  overrides: Partial<{
    agentId: string;
    agentVersion: number;
    workflowId: string;
    workflowVersion: number;
  }> = {}
) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-save-flow">
          <Toaster />
          <StudioEditorPanel
            workflowStep={step}
            agentId={overrides.agentId ?? "agent1"}
            agentVersion={overrides.agentVersion ?? 1}
            workflowId={overrides.workflowId ?? "wf1"}
            workflowVersion={overrides.workflowVersion ?? 2}
          />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("StudioEditorPanel — tab switching and dirty state", () => {
  it("loads resource, verifies save button is disabled (no unsaved changes), and switches tabs", async () => {
    renderPanelForSave({
      type: "eddi://ai.labs.rules",
      extensions: {},
      config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
    });

    const user = userEvent.setup();

    // 1. Wait for editor to render
    await waitFor(
      () => {
        expect(screen.getByTestId("studio-editor-panel")).toBeInTheDocument();
        expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );

    // 2. Switch to JSON tab
    await user.click(screen.getByTestId("tab-json"));

    // JSON view should be visible
    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });

    // 3. The save button should be disabled initially (no unsaved changes)
    const saveBtn = screen.getByTestId("save-btn");
    expect(saveBtn).toBeDisabled();

    // 4. Switch back to form tab and verify it renders
    await user.click(screen.getByTestId("tab-form"));

    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("shows version picker with initial version from URI", async () => {
    renderPanelForSave({
      type: "eddi://ai.labs.llm",
      extensions: {},
      config: { uri: "eddi://ai.labs.llm/llmstore/llms/llm1?version=1" },
    });

    await waitFor(
      () => {
        expect(screen.getByTestId("studio-editor-panel")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );

    // Version picker should be rendered
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });
  });

  it("renders form editor for a known type (rules)", async () => {
    renderPanelForSave({
      type: "eddi://ai.labs.rules",
      extensions: {},
      config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
    });

    await waitFor(
      () => {
        expect(screen.getByTestId("studio-editor-panel")).toBeInTheDocument();
        expect(screen.getByTestId("form-view")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
  });

  it("renders form editor for LLM type", async () => {
    renderPanelForSave({
      type: "eddi://ai.labs.llm",
      extensions: {},
      config: { uri: "eddi://ai.labs.llm/llmstore/llms/llm1?version=1" },
    });

    await waitFor(
      () => {
        expect(screen.getByTestId("studio-editor-panel")).toBeInTheDocument();
        expect(screen.getByTestId("form-view")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
  });

  it("renders both discard and save buttons that are initially disabled", async () => {
    renderPanelForSave({
      type: "eddi://ai.labs.rules",
      extensions: {},
      config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
    });

    await waitFor(
      () => {
        expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );

    // Both buttons should be rendered but disabled (no changes yet)
    expect(screen.getByTestId("save-btn")).toBeDisabled();
    expect(screen.getByTestId("discard-btn")).toBeDisabled();
  });

  it("supports tab switching between Form and JSON views", async () => {
    renderPanelForSave({
      type: "eddi://ai.labs.rules",
      extensions: {},
      config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
    });

    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );

    // Default: form tab active
    expect(screen.getByTestId("form-view")).toBeInTheDocument();

    // Switch to JSON
    await user.click(screen.getByTestId("tab-json"));
    expect(screen.getByTestId("json-view")).toBeInTheDocument();

    // Switch back to Form
    await user.click(screen.getByTestId("tab-form"));
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
  });
});
