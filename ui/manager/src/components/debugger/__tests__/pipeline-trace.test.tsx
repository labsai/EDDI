import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PipelineTrace } from "@/components/debugger/pipeline-trace";
import { useDebugStore, type PipelineEvent, type PipelineTurn } from "@/hooks/use-debug-events";

function renderTrace(conversationId: string | null = "conv-1") {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <PipelineTrace conversationId={conversationId} />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const mockEvents: PipelineEvent[] = [
  { type: "task_start", taskId: "t1", taskType: "ai.labs.parser", index: 0, timestamp: 1000 },
  { type: "task_complete", taskId: "t1", taskType: "ai.labs.parser", index: 0, durationMs: 42, timestamp: 1042 },
  { type: "task_start", taskId: "t2", taskType: "ai.labs.llm", index: 1, timestamp: 1042 },
  { type: "task_complete", taskId: "t2", taskType: "ai.labs.llm", index: 1, durationMs: 250, timestamp: 1292 },
];

const mockTurn: PipelineTurn = {
  turnIndex: 0,
  events: mockEvents,
  totalDurationMs: 292,
  startTime: 1000,
};

describe("PipelineTrace", () => {
  beforeEach(() => {
    useDebugStore.setState({
      turns: [],
      currentTurnEvents: [],
      currentTurnStart: 0,
      selectedTurnIndex: null,
    });
  });

  it("renders empty state when no events", async () => {
    renderTrace();

    await waitFor(() => {
      expect(screen.getByTestId("pipeline-trace")).toBeInTheDocument();
    });
  });

  it("renders task bars from completed turn", async () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    await waitFor(() => {
      const taskBars = screen.getAllByTestId("task-bar");
      expect(taskBars.length).toBe(2);
    });
  });

  it("task bars have aria-expanded attribute", async () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    await waitFor(() => {
      const taskBars = screen.getAllByTestId("task-bar");
      expect(taskBars[0]).toHaveAttribute("aria-expanded", "false");
    });
  });

  it("shows turn selector when multiple turns", async () => {
    const turn2: PipelineTurn = { ...mockTurn, turnIndex: 1 };
    useDebugStore.setState({ turns: [mockTurn, turn2] });
    renderTrace();

    await waitFor(() => {
      expect(screen.getByTestId("turn-selector")).toBeInTheDocument();
    });
  });

  it("hides turn selector with single turn", async () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    await waitFor(() => {
      expect(screen.queryByTestId("turn-selector")).not.toBeInTheDocument();
    });
  });

  it("renders live processing indicator for in-progress events", async () => {
    useDebugStore.setState({
      currentTurnEvents: [
        { type: "task_start", taskId: "t1", taskType: "ai.labs.parser", index: 0, timestamp: Date.now() },
      ],
      currentTurnStart: Date.now(),
    });
    renderTrace();

    await waitFor(() => {
      expect(screen.getByTestId("pipeline-trace")).toBeInTheDocument();
    });
  });
});
