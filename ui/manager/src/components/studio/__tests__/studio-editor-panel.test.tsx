import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
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

  it("shows 'no config' message when URI is missing", () => {
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
    expect(
      screen.getByText(
        "This pipeline stage has no configuration URI"
      )
    ).toBeInTheDocument();
  });

  it("shows 'unsupported type' for unknown extension types with URI", () => {
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
    expect(
      screen.getByText(
        "Editor not available for this extension type"
      )
    ).toBeInTheDocument();
    expect(screen.getByText("eddi://ai.labs.unknown_type")).toBeInTheDocument();
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
    expect(screen.getByText("ai.labs.parser")).toBeInTheDocument();
  });
});
