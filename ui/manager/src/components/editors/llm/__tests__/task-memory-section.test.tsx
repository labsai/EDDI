import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TaskMemorySection } from "@/components/editors/llm/task-memory-section";
import type { LlmTask } from "@/components/editors/llm/types";

// Mock monaco (used by ContentEditor)
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(({ value }: { value: string }) => (
    <textarea data-testid="mock-monaco-textarea" defaultValue={value} />
  )),
}));

const emptyTask: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
};

const taskWithSummary: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
  conversationSummary: {
    enabled: true,
    llmProvider: "anthropic",
    llmModel: "claude-sonnet-4-6",
    maxSummaryTokens: 800,
    recentWindowSteps: 5,
    maxRecallTurns: 20,
  },
};

const taskWithTokenWindow: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
  maxContextTokens: 4096,
  anchorFirstSteps: 3,
};

describe("TaskMemorySection", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders Token-Aware Context Window section", () => {
    renderWithProviders(
      <TaskMemorySection task={emptyTask} onChange={onChange} />
    );
    expect(
      screen.getByText("Token-Aware Context Window")
    ).toBeInTheDocument();
  });

  it("renders Conversation Memory section", () => {
    renderWithProviders(
      <TaskMemorySection task={emptyTask} onChange={onChange} />
    );
    expect(screen.getByText("Conversation Memory")).toBeInTheDocument();
  });

  it("offers a self-hosted summary provider only when the task itself is openai-compatible", async () => {
    const user = userEvent.setup();
    const options = () =>
      Array.from((screen.getByTestId("summary-provider-select") as HTMLSelectElement).options).map((o) => o.value);

    const view = renderWithProviders(<TaskMemorySection task={taskWithSummary} onChange={onChange} />);
    if (!screen.queryByTestId("summary-provider-select")) await user.click(screen.getByText("Conversation Memory"));
    // No base URL field here, and an openai task has none to inherit.
    expect(options()).not.toContain("openai-compatible");
    expect(options()).toContain("ollama");
    view.unmount();

    renderWithProviders(
      <TaskMemorySection
        task={{ ...taskWithSummary, type: "openai-compatible", parameters: { baseUrl: "http://vllm:8000/v1", modelName: "m" } }}
        onChange={onChange}
      />,
    );
    if (!screen.queryByTestId("summary-provider-select")) await user.click(screen.getByText("Conversation Memory"));
    // Same provider as the task: the summarizer inherits its baseUrl and key.
    expect(options()).toContain("openai-compatible");
  });

  it("renders Retry Configuration section", () => {
    renderWithProviders(
      <TaskMemorySection task={emptyTask} onChange={onChange} />
    );
    expect(screen.getByText("Retry Configuration")).toBeInTheDocument();
  });

  it("shows token window inputs when section is expanded", async () => {
    userEvent.setup();
    renderWithProviders(
      <TaskMemorySection task={taskWithTokenWindow} onChange={onChange} />
    );
    // The token window section should be auto-open since maxContextTokens > 0
    expect(screen.getByTestId("token-window-section")).toBeInTheDocument();
    expect(screen.getByTestId("max-context-tokens")).toHaveValue(4096);
    expect(screen.getByTestId("anchor-first-steps")).toHaveValue(3);
  });

  it("shows summary enable checkbox", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskMemorySection task={emptyTask} onChange={onChange} />
    );
    // Click to expand Conversation Memory section
    await user.click(screen.getByText("Conversation Memory"));
    expect(screen.getByTestId("summary-enable")).toBeInTheDocument();
  });

  it("shows summary config fields when summary is enabled", () => {
    renderWithProviders(
      <TaskMemorySection task={taskWithSummary} onChange={onChange} />
    );
    // conversationSummary.enabled = true, so section should auto-open
    expect(screen.getByTestId("conversation-memory-section")).toBeInTheDocument();
    expect(screen.getByText("Summary Provider")).toBeInTheDocument();
    expect(screen.getByText("Summary Model")).toBeInTheDocument();
  });

  it("shows retry section inputs when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskMemorySection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("Retry Configuration"));
    expect(screen.getByTestId("retry-section")).toBeInTheDocument();
    expect(screen.getByText("Max Attempts")).toBeInTheDocument();
  });

  it("calls onChange when enabling summary", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskMemorySection task={emptyTask} onChange={onChange} />
    );
    // Expand the section
    await user.click(screen.getByText("Conversation Memory"));
    // Check the enable checkbox
    await user.click(screen.getByTestId("summary-enable"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        conversationSummary: expect.objectContaining({
          enabled: true,
        }),
      })
    );
  });
});
