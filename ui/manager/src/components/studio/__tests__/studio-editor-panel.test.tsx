import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import {
  StudioEditorPanel,
  StudioEditorEmpty,
} from "@/components/studio/studio-editor-panel";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

// Mock monaco editor since it's externalized
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(() => <div data-testid="monaco-editor" />),
  DiffEditor: vi.fn(() => <div data-testid="monaco-diff-editor" />),
}));

describe("StudioEditorEmpty", () => {
  it("renders placeholder message", () => {
    renderWithProviders(<StudioEditorEmpty />);
    expect(
      screen.getByText("Click a pipeline stage to open its editor")
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Select any extension in the pipeline to view and edit its configuration inline"
      )
    ).toBeInTheDocument();
  });
});

describe("StudioEditorPanel", () => {
  const defaultProps = {
    agentId: "agent1",
    agentVersion: 1,
    workflowId: "wf1",
    workflowVersion: 2,
  };

  it("explains a store-backed step that references no resource", () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.rules",
          extensions: {},
          config: {},
        }}
      />
    );
    expect(screen.getByTestId("studio-step-info")).toHaveAttribute("data-kind", "noConfig");
    expect(screen.queryByTestId("studio-step-info-json")).not.toBeInTheDocument();
    expect(screen.getByTestId("studio-step-info-open-workflow")).toHaveAttribute(
      "href",
      "/manage/workflowview/wf1?version=2",
    );
  });

  it("shows an unknown step type's stored configuration read-only", () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.unknown_type",
          extensions: {},
          config: { uri: "eddi://ai.labs.unknown/unknownstore/items/id1?version=1" },
        }}
      />
    );
    expect(screen.getByTestId("studio-step-info")).toHaveAttribute("data-kind", "unknown");
    expect(screen.getByTestId("studio-step-info-type")).toHaveTextContent("eddi://ai.labs.unknown_type");
    expect(screen.getByTestId("studio-step-info-json")).toHaveTextContent("unknownstore/items/id1");
  });

  // The user-reported bug: `ai.labs.httpcalls` is the API-call task's canonical
  // id (ApiCallsTask.ID), yet the Studio's own type table only knew the alias,
  // so every httpcalls step said "Editor not available".
  it.each([
    ["eddi://ai.labs.httpcalls", "eddi://ai.labs.apicalls/apicallstore/apicalls/6aed18496ddb7a7697be8?version=1"],
    ["eddi://ai.labs.apicalls", "eddi://ai.labs.apicalls/apicallstore/apicalls/6aed18496ddb7a7697be8?version=1"],
    ["eddi://ai.labs.httpcalls", "eddi://ai.labs.httpcalls/apicallstore/apicalls/6aed18496ddb7a7697be8?version=1"],
  ])("opens the API-calls editor for a %s step (%s)", async (type, uri) => {
    renderWithProviders(
      <StudioEditorPanel {...defaultProps} workflowStep={{ type, extensions: {}, config: { uri } }} />
    );
    expect(await screen.findByTestId("apicalls-editor")).toBeInTheDocument();
    expect(screen.queryByTestId("studio-step-info")).not.toBeInTheDocument();
  });

  it("opens the LLM editor for a step still typed ai.labs.langchain", async () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.langchain",
          extensions: {},
          config: { uri: "eddi://ai.labs.langchain/llmstore/llms/llm1?version=1" },
        }}
      />
    );
    expect(await screen.findByTestId("llm-task-editor")).toBeInTheDocument();
  });

  it("describes a templating step instead of warning about it", () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{ type: "eddi://ai.labs.templating", extensions: {}, config: {} }}
      />
    );
    expect(screen.getByTestId("studio-step-info")).toHaveAttribute("data-kind", "templating");
    expect(screen.queryByTestId("studio-step-info-json")).not.toBeInTheDocument();
  });

  it("shows a parser's embedded configuration read-only", () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.parser",
          extensions: { dictionaries: [{ type: "eddi://ai.labs.parser.dictionaries.integer" }] },
          config: { appendExpressions: true },
        }}
      />
    );
    expect(screen.getByTestId("studio-step-info")).toHaveAttribute("data-kind", "inline");
    const json = screen.getByTestId("studio-step-info-json");
    expect(json).toHaveTextContent("appendExpressions");
    expect(json).toHaveTextContent("ai.labs.parser.dictionaries.integer");
  });

  it("shows loading skeleton when fetching resource", () => {
    server.use(
      http.get("*/rulestore/rulesets/:id", () => {
        return new Promise(() => {}); // never resolve
      })
    );
    const { container } = renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.rules",
          extensions: {},
          config: {
            uri: "eddi://ai.labs.behavior/rulestore/rulesets/rule-loading?version=1",
          },
        }}
      />
    );
    // Skeleton elements
    const skeletons = container.querySelectorAll("[class*='animate-pulse']");
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it("shows error state when resource fails to load", async () => {
    server.use(
      http.get("*/rulestore/rulesets/:id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.rules",
          extensions: {},
          config: {
            uri: "eddi://ai.labs.behavior/rulestore/rulesets/rule-err?version=1",
          },
        }}
      />
    );
    await waitFor(() => {
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    });
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });

  // agent1 and agent6 share wf1, so switching between them keeps the panel
  // mounted; a tick given for one agent's next version must not carry over.
  it("drops the compatibility tick when the agent changes", async () => {
    const step = {
      type: "eddi://ai.labs.rules",
      extensions: {},
      config: { uri: "eddi://ai.labs.behavior/rulestore/rulesets/rules-1?version=1" },
    };
    const { rerender } = renderWithProviders(<StudioEditorPanel {...defaultProps} workflowStep={step} />);
    const box = (await screen.findByTestId("compatible-version-checkbox")) as HTMLInputElement;
    fireEvent.click(box);
    expect(box.checked).toBe(true);

    rerender(<StudioEditorPanel {...defaultProps} agentId="agent6" workflowStep={step} />);

    await waitFor(() =>
      expect((screen.getByTestId("compatible-version-checkbox") as HTMLInputElement).checked).toBe(false),
    );
  });

  it("renders editor panel when data loads successfully", async () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "eddi://ai.labs.rules",
          extensions: {},
          config: {
            uri: "eddi://ai.labs.behavior/rulestore/rulesets/rules-1?version=1",
          },
        }}
      />
    );
    await waitFor(() => {
      expect(
        screen.getByTestId("studio-editor-panel")
      ).toBeInTheDocument();
    });
  });

  it("displays the extension type name when URI is empty", () => {
    renderWithProviders(
      <StudioEditorPanel
        {...defaultProps}
        workflowStep={{
          type: "ai.labs.parser",
          extensions: {},
          config: {},
        }}
      />
    );
    expect(screen.getByTestId("studio-step-info-type")).toHaveTextContent("ai.labs.parser");
  });
});
