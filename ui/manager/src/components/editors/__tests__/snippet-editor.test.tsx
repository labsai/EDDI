import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import {
  SnippetEditor,
  type PromptSnippetConfig,
} from "@/components/editors/snippet-editor";

// ContentEditor uses monaco, mock it
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(({ value }: { value: string }) => (
    <textarea data-testid="mock-monaco" defaultValue={value} />
  )),
}));

const emptyConfig: PromptSnippetConfig = {};

const populatedConfig: PromptSnippetConfig = {
  name: "cautious_mode",
  category: "governance",
  description: "Makes the agent cautious",
  content: "You should be cautious in your responses.",
  tags: ["safety", "production"],
  templateEnabled: true,
};

describe("SnippetEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid snippet-editor", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("snippet-editor")).toBeInTheDocument();
  });

  it("shows snippet name input", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("snippet-name")).toBeInTheDocument();
  });

  it("shows category select", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("snippet-category")).toBeInTheDocument();
  });

  it("shows description input", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("snippet-description")).toBeInTheDocument();
  });

  it("shows template enabled checkbox", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("snippet-template-enabled")).toBeInTheDocument();
  });

  it("shows Identity section", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Identity")).toBeInTheDocument();
  });

  it("shows Prompt Content section", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Prompt Content")).toBeInTheDocument();
  });

  it("renders populated config values", () => {
    renderWithProviders(
      <SnippetEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("snippet-name")).toHaveValue("cautious_mode");
    expect(screen.getByTestId("snippet-description")).toHaveValue("Makes the agent cautious");
  });

  it("shows usage hint when name is set", () => {
    renderWithProviders(
      <SnippetEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText(/snippets\.cautious_mode/)).toBeInTheDocument();
  });

  it("shows tags for populated config", () => {
    renderWithProviders(
      <SnippetEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("safety")).toBeInTheDocument();
    expect(screen.getByText("production")).toBeInTheDocument();
  });

  it("shows template resolution hint text", () => {
    renderWithProviders(
      <SnippetEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText(/template resolution/)).toBeInTheDocument();
  });
});
