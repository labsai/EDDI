import type { ComponentProps } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, fireEvent, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
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
    workflowSteps: ComponentProps<typeof PipelineRailroad>["workflowSteps"];
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

  it("shows each step's resource descriptor NAME, keeping the id in the tooltip", async () => {
    renderRailroad();
    // The MSW descriptor handler answers "Mock descriptor <id>".
    await waitFor(() => expect(screen.getByTestId("stage-0-name")).toHaveTextContent("Mock descriptor p1"));
    expect(screen.getByTestId("stage-1-name")).toHaveTextContent("Mock descriptor lc1");
    expect(screen.getByTestId("stage-0")).toHaveAttribute("title", expect.stringContaining("p1 · v1"));
  });

  it("falls back to the resource id while (or when) no name resolves", () => {
    server.use(http.get("*/descriptorstore/descriptors/:id", () => new HttpResponse(null, { status: 404 })));
    renderRailroad();
    expect(screen.getByTestId("stage-2-name")).toHaveTextContent("o1");
  });

  it("names a step without a resource by its type", () => {
    renderRailroad({ workflowSteps: [{ type: "eddi://ai.labs.templating", extensions: {}, config: {} }] });
    expect(screen.getByTestId("stage-0-name")).toHaveTextContent("Templating");
  });

  it("gathers consecutive steps of one type under a single header with a count", () => {
    const apiStep = (i: number) => ({
      type: "eddi://ai.labs.httpcalls",
      extensions: {},
      config: { uri: `eddi://ai.labs.apicalls/apicallstore/apicalls/a${i}?version=1` },
    });
    renderRailroad({
      workflowSteps: [mockSteps[0]!, apiStep(1), apiStep(2), apiStep(3), mockSteps[2]!],
    });
    expect(screen.getByTestId("stage-group-1")).toBeInTheDocument();
    expect(screen.getByTestId("stage-group-1-count")).toHaveTextContent("3");
    // Every step is still its own selectable row, numbered by pipeline position.
    for (const i of [0, 1, 2, 3, 4]) expect(screen.getByTestId(`stage-${i}`)).toBeInTheDocument();
    expect(screen.queryByTestId("stage-group-0")).not.toBeInTheDocument();
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

    // Stage 1 was running, then failed → error: no longer running
    expect(screen.getByTestId("stage-1")).toHaveAttribute("data-status", "error");
    expect(screen.getByTestId("stage-1-status").className).not.toContain("animate-pulse");
    // The error did NOT land on the payload's index (0) — stage 0 stays idle
    expect(screen.getByTestId("stage-0")).toHaveAttribute("data-status", "idle");
  });
});
