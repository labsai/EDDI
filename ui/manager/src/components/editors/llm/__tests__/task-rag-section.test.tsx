import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TaskRagSection } from "@/components/editors/llm/task-rag-section";
import type { LlmTask } from "@/components/editors/llm/types";

const emptyTask: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
};

const taskWithKB: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
  knowledgeBases: [
    { name: "product-docs", maxResults: 5, minScore: 0.6 },
  ],
};

const taskWithLegacyRag: LlmTask = {
  type: "openai",
  actions: [],
  parameters: {},
  retrievalAugmentor: {
    httpCall: "search",
    embeddingModel: "text-embed-3",
    embeddingStore: "qdrant",
  },
};

describe("TaskRagSection", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders RAG section label", () => {
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    expect(
      screen.getByText("RAG (Knowledge Retrieval)")
    ).toBeInTheDocument();
  });

  it("shows RAG section content when expanded", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.getByTestId("rag-section")).toBeInTheDocument();
    expect(screen.getByText("Knowledge Bases")).toBeInTheDocument();
    expect(
      screen.getByText("Auto-Discover Workflow RAG")
    ).toBeInTheDocument();
    expect(
      screen.getByText("httpCall RAG (Zero Infrastructure)")
    ).toBeInTheDocument();
  });

  it("shows no knowledge bases message when empty", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(
      screen.getByText("No knowledge bases referenced")
    ).toBeInTheDocument();
  });

  it("shows add KB button", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.getByTestId("add-kb-ref")).toBeInTheDocument();
  });

  it("hides add KB button in readOnly mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} readOnly />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.queryByTestId("add-kb-ref")).not.toBeInTheDocument();
  });

  it("calls onChange when add KB is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    await user.click(screen.getByTestId("add-kb-ref"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        knowledgeBases: [
          expect.objectContaining({
            name: "",
            maxResults: 5,
            minScore: 0.6,
          }),
        ],
      })
    );
  });

  it("shows KB name input for populated task", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={taskWithKB} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.getByTestId("kb-name-0")).toHaveValue("product-docs");
  });

  it("shows enable workflow RAG checkbox", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.getByTestId("enable-workflow-rag")).toBeInTheDocument();
  });

  it("shows httpcall rag input", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={emptyTask} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.getByTestId("httpcall-rag")).toBeInTheDocument();
  });

  it("shows legacy RAG warning when retrievalAugmentor is present", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TaskRagSection task={taskWithLegacyRag} onChange={onChange} />
    );
    await user.click(screen.getByText("RAG (Knowledge Retrieval)"));
    expect(screen.getByText("Legacy RAG (deprecated)")).toBeInTheDocument();
    expect(
      screen.getByText("Remove Legacy Config")
    ).toBeInTheDocument();
  });
});
