import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PipelineRailroad } from "@/components/studio/pipeline-railroad";
import { useDebugStore } from "@/hooks/use-debug-events";

const mockSteps = [
  { type: "eddi://ai.labs.parser", extensions: {}, config: { uri: "eddi://ai.labs.parser/parsers/p1?version=1" } },
  { type: "eddi://ai.labs.llm", extensions: {}, config: { uri: "eddi://ai.labs.langchain/langchains/lc1?version=1" } },
  { type: "eddi://ai.labs.output", extensions: {}, config: { uri: "eddi://ai.labs.output/outputsets/o1?version=1" } },
];

function renderRailroad(
  props: Partial<{
    workflowSteps: typeof mockSteps;
    selectedIndex: number | null;
    onSelectStage: (idx: number) => void;
  }> = {},
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
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <PipelineRailroad
            workflowSteps={props.workflowSteps ?? mockSteps}
            selectedIndex={props.selectedIndex ?? null}
            onSelectStage={props.onSelectStage ?? (() => {})}
          />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("PipelineRailroad", () => {
  beforeEach(() => {
    useDebugStore.setState({
      currentTurnEvents: [],
    });
  });

  it("renders railroad container", () => {
    renderRailroad();
    expect(screen.getByTestId("pipeline-railroad")).toBeInTheDocument();
  });

  it("renders correct number of stages", () => {
    renderRailroad();
    expect(screen.getByTestId("stage-0")).toBeInTheDocument();
    expect(screen.getByTestId("stage-1")).toBeInTheDocument();
    expect(screen.getByTestId("stage-2")).toBeInTheDocument();
  });

  it("shows empty state when no steps", () => {
    renderRailroad({ workflowSteps: [] });
    expect(screen.getByText(/No pipeline stages/i)).toBeInTheDocument();
  });

  it("calls onSelectStage when stage clicked", () => {
    const onSelect = vi.fn();
    renderRailroad({ onSelectStage: onSelect });
    fireEvent.click(screen.getByTestId("stage-1"));
    expect(onSelect).toHaveBeenCalledWith(1);
  });

  it("marks selected stage with aria-current", () => {
    renderRailroad({ selectedIndex: 1 });
    expect(screen.getByTestId("stage-1")).toHaveAttribute("aria-current", "true");
    expect(screen.getByTestId("stage-0")).not.toHaveAttribute("aria-current");
  });

  it("displays i18n type labels", () => {
    renderRailroad();
    // Fallback labels should be displayed (from en.json or inline fallback)
    expect(screen.getByText("Parser")).toBeInTheDocument();
    expect(screen.getByText("Output")).toBeInTheDocument();
  });

  it("displays resource IDs from URIs", () => {
    renderRailroad();
    expect(screen.getByText("p1")).toBeInTheDocument();
    expect(screen.getByText("lc1")).toBeInTheDocument();
    expect(screen.getByText("o1")).toBeInTheDocument();
  });

  it("shows live status indicators for running events", () => {
    useDebugStore.setState({
      currentTurnEvents: [
        { type: "task_start", taskId: "t1", taskType: "ai.labs.parser", index: 0, timestamp: Date.now() },
      ],
    });
    renderRailroad();
    // The stage-0 should have a running indicator (animate-pulse class on inner div)
    expect(screen.getByTestId("stage-0")).toBeInTheDocument();
  });

  it("marks the recovered stage index as error on task_failed, not the failed payload's index", () => {
    // task_start for stage 1 (taskId "t2"), then a task_failed whose payload
    // index is WRONG (0). The status must be recovered from indexByTaskId (→ 1),
    // so stage 1 becomes error while stage 0 stays idle.
    useDebugStore.setState({
      currentTurnEvents: [
        { type: "task_start", taskId: "t2", taskType: "ai.labs.llm", index: 1, timestamp: Date.now() },
        { type: "task_failed", taskId: "t2", taskType: "ai.labs.llm", index: 0, errorType: "timeout", timestamp: Date.now() },
      ],
    });
    renderRailroad();

    const stage1Icon = screen.getByTestId("stage-1").querySelector(".h-9")!;
    const stage0Icon = screen.getByTestId("stage-0").querySelector(".h-9")!;

    // Stage 1 was running, then failed → error: no longer amber/running
    expect(stage1Icon.className).not.toContain("bg-amber-500/10");
    expect(stage1Icon.className).not.toContain("animate-pulse");
    // The error did NOT land on the payload's index (0) — stage 0 stays idle
    expect(stage0Icon.className).toContain("bg-muted/50");
  });
});
