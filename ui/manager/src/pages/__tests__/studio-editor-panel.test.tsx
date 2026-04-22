import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import {
  StudioEditorPanel,
  StudioEditorEmpty,
} from "@/components/studio/studio-editor-panel";

function renderPanel(
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
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-studio-panel">
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

function renderEmpty() {
  return render(
    <MemoryRouter>
      <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test-studio-empty">
        <StudioEditorEmpty />
      </ThemeProvider>
    </MemoryRouter>
  );
}

describe("StudioEditorPanel", () => {
  it("renders the editor panel for an LLM step", async () => {
    renderPanel({
      type: "ai.labs.llm",
      extensions: {},
      config: { uri: "eddi://ai.labs.llm/llmstore/llms/llm1?version=1" },
    });

    await waitFor(
      () => {
        expect(screen.getByTestId("studio-editor-panel")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
  });

  it("renders the editor panel for a rules step", async () => {
    renderPanel({
      type: "ai.labs.rules",
      extensions: {},
      config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" },
    });

    await waitFor(
      () => {
        expect(screen.getByTestId("studio-editor-panel")).toBeInTheDocument();
      },
      { timeout: 5000 }
    );
  });

  it("shows unsupported type message for unknown extension", async () => {
    renderPanel({
      type: "ai.labs.unknown",
      extensions: {},
      config: { uri: "eddi://ai.labs.unknown/store/items/id1?version=1" },
    });

    await waitFor(() => {
      expect(
        screen.getByText(/Editor not available/i)
      ).toBeInTheDocument();
    });
  });

  it("shows no-config message when URI is missing", async () => {
    renderPanel({
      type: "ai.labs.llm",
      extensions: {},
      config: {},
    });

    await waitFor(() => {
      expect(
        screen.getByText(/no configuration URI/i)
      ).toBeInTheDocument();
    });
  });

  it("displays the extension type in the unsupported fallback", async () => {
    renderPanel({
      type: "ai.labs.newtype",
      extensions: {},
      config: { uri: "eddi://ai.labs.newtype/store/items/id1?version=1" },
    });

    await waitFor(() => {
      expect(
        screen.getByText("ai.labs.newtype")
      ).toBeInTheDocument();
    });
  });

  it("shows unsupported type for parser extension (no standalone editor)", async () => {
    renderPanel({
      type: "ai.labs.parser",
      extensions: {},
      config: { uri: "eddi://ai.labs.parser/parserstore/parsers/parser1?version=1" },
    });

    // Parsers don't have a standalone editor — they're infrastructure that
    // binds dictionaries to the pipeline. The old parser→dictionary mapping
    // was incorrect (it sent a parser ID to the dictionary store).
    await waitFor(() => {
      expect(
        screen.getByText(/Editor not available/i)
      ).toBeInTheDocument();
    });
  });

  it("maps output.template to output slug", async () => {
    renderPanel({
      type: "ai.labs.output.template",
      extensions: {},
      config: { uri: "eddi://ai.labs.output.template/outputstore/outputsets/out1?version=1" },
    });

    await waitFor(() => {
      const unsupported = screen.queryByText(/Editor not available/i);
      expect(unsupported).not.toBeInTheDocument();
    });
  });
});

describe("StudioEditorEmpty", () => {
  it("renders the empty placeholder with call-to-action", () => {
    renderEmpty();
    expect(
      screen.getByText(/Click a pipeline stage/i)
    ).toBeInTheDocument();
  });

  it("renders the helper hint text", () => {
    renderEmpty();
    expect(
      screen.getByText(/Select any extension/i)
    ).toBeInTheDocument();
  });
});
