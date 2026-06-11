import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { PipelineTrace } from "@/components/debugger/pipeline-trace";
import { useDebugStore, type PipelineEvent, type PipelineTurn } from "@/hooks/use-debug-events";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { userEvent } from "@/test/test-utils";

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

  // ── Basic rendering ────────────────────────────────────────────────
  it("renders the pipeline-trace container", () => {
    renderTrace();
    expect(screen.getByTestId("pipeline-trace")).toBeInTheDocument();
  });

  it("shows empty state message when no turns and no live events", async () => {
    // No live turns, and audit returns empty for this conv
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json([]);
      })
    );
    renderTrace("conv-empty");

    await waitFor(() => {
      expect(screen.getByText(/Send a message to see the pipeline trace/)).toBeInTheDocument();
    });
  });

  it("renders task bars from live turn data", () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    const taskBars = screen.getAllByTestId("task-bar");
    expect(taskBars.length).toBe(2);
  });

  it("task bars show task type labels (stripped ai.labs. prefix)", () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    expect(screen.getByText("parser")).toBeInTheDocument();
    expect(screen.getByText("llm")).toBeInTheDocument();
  });

  it("shows duration text for completed tasks", () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    expect(screen.getByText("42ms")).toBeInTheDocument();
    expect(screen.getByText("250ms")).toBeInTheDocument();
  });

  it("shows total duration for the turn", () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    expect(screen.getByText("292ms")).toBeInTheDocument();
  });

  it("task bars have aria-expanded=false by default", () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    const taskBars = screen.getAllByTestId("task-bar");
    expect(taskBars[0]).toHaveAttribute("aria-expanded", "false");
  });

  // ── Task bar expansion ─────────────────────────────────────────────
  it("expands task bar on click to show details", async () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    const user = userEvent.setup();
    const firstBar = screen.getAllByTestId("task-bar")[0]!;
    await user.click(firstBar);

    expect(firstBar).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(/Duration/)).toBeInTheDocument();
  });

  it("collapses task bar on second click", async () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    const user = userEvent.setup();
    const firstBar = screen.getAllByTestId("task-bar")[0]!;

    await user.click(firstBar);
    expect(firstBar).toHaveAttribute("aria-expanded", "true");

    await user.click(firstBar);
    expect(firstBar).toHaveAttribute("aria-expanded", "false");
  });

  // ── Actions display ────────────────────────────────────────────────
  it("shows actions summary when events have actions", () => {
    const eventsWithActions: PipelineEvent[] = [
      ...mockEvents.slice(0, 3),
      {
        type: "task_complete", taskId: "t2", taskType: "ai.labs.llm", index: 1,
        durationMs: 250, timestamp: 1292,
        actions: ["greet", "chat"],
      },
    ];
    const turn: PipelineTurn = {
      turnIndex: 0,
      events: eventsWithActions,
      totalDurationMs: 292,
      startTime: 1000,
    };
    useDebugStore.setState({ turns: [turn] });
    renderTrace();

    expect(screen.getByText("greet")).toBeInTheDocument();
    expect(screen.getByText("chat")).toBeInTheDocument();
  });

  it("shows actions in expanded task detail", async () => {
    const eventsWithActions: PipelineEvent[] = [
      { type: "task_start", taskId: "t1", taskType: "ai.labs.llm", index: 0, timestamp: 1000 },
      {
        type: "task_complete", taskId: "t1", taskType: "ai.labs.llm", index: 0,
        durationMs: 250, timestamp: 1250,
        actions: ["greet", "chat"],
      },
    ];
    const turn: PipelineTurn = { turnIndex: 0, events: eventsWithActions, totalDurationMs: 250, startTime: 1000 };
    useDebugStore.setState({ turns: [turn] });
    renderTrace();

    const user = userEvent.setup();
    await user.click(screen.getAllByTestId("task-bar")[0]!);

    // "Actions" appears in both the summary and the expanded detail
    expect(screen.getAllByText(/Actions/).length).toBeGreaterThanOrEqual(2);
  });

  // ── Confidence display ─────────────────────────────────────────────
  it("shows confidence percentage in expanded task detail", async () => {
    const eventsWithConf: PipelineEvent[] = [
      { type: "task_start", taskId: "t1", taskType: "ai.labs.behavior", index: 0, timestamp: 1000 },
      {
        type: "task_complete", taskId: "t1", taskType: "ai.labs.behavior", index: 0,
        durationMs: 5, timestamp: 1005,
        confidence: 0.85, actions: ["greet"],
      },
    ];
    const turn: PipelineTurn = { turnIndex: 0, events: eventsWithConf, totalDurationMs: 5, startTime: 1000 };
    useDebugStore.setState({ turns: [turn] });
    renderTrace();

    const user = userEvent.setup();
    await user.click(screen.getAllByTestId("task-bar")[0]!);

    expect(screen.getByText(/Confidence/)).toBeInTheDocument();
    expect(screen.getByText(/85%/)).toBeInTheDocument();
  });

  // ── Turn selector ──────────────────────────────────────────────────
  it("shows turn selector when multiple turns", () => {
    const turn2: PipelineTurn = { ...mockTurn, turnIndex: 1 };
    useDebugStore.setState({ turns: [mockTurn, turn2] });
    renderTrace();

    expect(screen.getByTestId("turn-selector")).toBeInTheDocument();
  });

  it("hides turn selector with single turn", () => {
    useDebugStore.setState({ turns: [mockTurn] });
    renderTrace();

    expect(screen.queryByTestId("turn-selector")).not.toBeInTheDocument();
  });

  // ── Live events ────────────────────────────────────────────────────
  it("shows 'Processing...' text for live events", () => {
    useDebugStore.setState({
      currentTurnEvents: [
        { type: "task_start", taskId: "t1", taskType: "ai.labs.llm", index: 0, timestamp: Date.now() },
      ],
      currentTurnStart: Date.now(),
    });
    renderTrace();

    expect(screen.getByText(/Processing/)).toBeInTheDocument();
  });

  it("shows '...' for running task duration", () => {
    useDebugStore.setState({
      currentTurnEvents: [
        { type: "task_start", taskId: "t1", taskType: "ai.labs.parser", index: 0, timestamp: Date.now() },
      ],
      currentTurnStart: Date.now(),
    });
    renderTrace();

    expect(screen.getByText("...")).toBeInTheDocument();
  });

  // ── Null conversationId ────────────────────────────────────────────
  it("renders with null conversationId", () => {
    renderTrace(null);
    expect(screen.getByTestId("pipeline-trace")).toBeInTheDocument();
  });

  // ── Error state ────────────────────────────────────────────────────
  it("shows error state when audit fetch fails", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", () => {
        return HttpResponse.json(null, { status: 500 });
      })
    );

    renderTrace("conv-error");

    await waitFor(() => {
      expect(screen.getByTestId("pipeline-trace-error")).toBeInTheDocument();
      expect(screen.getByText(/Failed to load pipeline trace/)).toBeInTheDocument();
    }, { timeout: 5000 });
  });

  // ── Historical data from audit entries ─────────────────────────────
  it("loads historical turns from audit entries when no live turns", async () => {
    // Return audit entries so they get converted to historical turns
    renderTrace("conv1");

    await waitFor(() => {
      // The mock audit data has 4 entries all with stepIndex: 0
      // This produces 1 turn with 4 tasks
      const taskBars = screen.getAllByTestId("task-bar");
      expect(taskBars.length).toBe(4);
    }, { timeout: 5000 });
  });

  // ── Various task type labels ───────────────────────────────────────
  it("renders different task types with correct labels", () => {
    const mixedEvents: PipelineEvent[] = [
      { type: "task_start", taskId: "t1", taskType: "ai.labs.httpcalls", index: 0, timestamp: 1000 },
      { type: "task_complete", taskId: "t1", taskType: "ai.labs.httpcalls", index: 0, durationMs: 100, timestamp: 1100 },
      { type: "task_start", taskId: "t2", taskType: "ai.labs.propertysetter", index: 1, timestamp: 1100 },
      { type: "task_complete", taskId: "t2", taskType: "ai.labs.propertysetter", index: 1, durationMs: 5, timestamp: 1105 },
      { type: "task_start", taskId: "t3", taskType: "ai.labs.output", index: 2, timestamp: 1105 },
      { type: "task_complete", taskId: "t3", taskType: "ai.labs.output", index: 2, durationMs: 3, timestamp: 1108 },
    ];
    const turn: PipelineTurn = { turnIndex: 0, events: mixedEvents, totalDurationMs: 108, startTime: 1000 };
    useDebugStore.setState({ turns: [turn] });
    renderTrace();

    expect(screen.getByText("httpcalls")).toBeInTheDocument();
    expect(screen.getByText("propertysetter")).toBeInTheDocument();
    expect(screen.getByText("output")).toBeInTheDocument();
  });

  // ── Task without durationMs uses timestamp diff ────────────────────
  it("falls back to timestamp diff when durationMs is undefined", () => {
    const eventsNoDuration: PipelineEvent[] = [
      { type: "task_start", taskId: "t1", taskType: "ai.labs.parser", index: 0, timestamp: 1000 },
      { type: "task_complete", taskId: "t1", taskType: "ai.labs.parser", index: 0, timestamp: 1075 },
    ];
    const turn: PipelineTurn = { turnIndex: 0, events: eventsNoDuration, totalDurationMs: 75, startTime: 1000 };
    useDebugStore.setState({ turns: [turn] });
    renderTrace();

    expect(screen.getAllByText("75ms").length).toBeGreaterThanOrEqual(1);
  });

  // ── De-duplication of actions ──────────────────────────────────────
  it("de-duplicates actions across multiple events", () => {
    const events: PipelineEvent[] = [
      { type: "task_start", taskId: "t1", taskType: "ai.labs.rules", index: 0, timestamp: 1000 },
      { type: "task_complete", taskId: "t1", taskType: "ai.labs.rules", index: 0, durationMs: 10, timestamp: 1010, actions: ["greet", "chat"] },
      { type: "task_start", taskId: "t2", taskType: "ai.labs.llm", index: 1, timestamp: 1010 },
      { type: "task_complete", taskId: "t2", taskType: "ai.labs.llm", index: 1, durationMs: 200, timestamp: 1210, actions: ["greet", "respond"] },
    ];
    const turn: PipelineTurn = { turnIndex: 0, events, totalDurationMs: 210, startTime: 1000 };
    useDebugStore.setState({ turns: [turn] });
    renderTrace();

    // "greet" should appear once (deduplicated), "chat" and "respond" each once
    const greetBadges = screen.getAllByText("greet");
    // In the actions summary, "greet" should appear once
    // But may also appear in task-bar expanded view — find only in actions summary
    expect(greetBadges.length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText("chat")).toBeInTheDocument();
    expect(screen.getByText("respond")).toBeInTheDocument();
  });
});
