import { describe, it, expect, vi, beforeEach, beforeAll } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatActivity } from "../chat-activity";
import type { PipelineEvent } from "@/hooks/use-debug-events";

describe("ChatActivity", () => {
  const mockWriteText = vi.fn();

  beforeAll(() => {
    if (typeof navigator !== "undefined") {
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText: mockWriteText },
        writable: true,
        configurable: true,
      });
    }
  });

  beforeEach(() => {
    mockWriteText.mockReset();
  });

  it("renders nothing when events are empty", () => {
    const { container } = renderWithProviders(
      <ChatActivity events={[]} isLive={false} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders processing state when hasRunning is true and isLive is true", () => {
    const events: PipelineEvent[] = [
      {
        type: "task_start",
        taskType: "ai.labs.rules",
        taskId: "1",
        index: 0,
        timestamp: Date.now(),
      },
    ];

    // End-user live mode is now a single status line — no step fraction, no
    // rows. The classic Processing bar lives on the debug surface.
    renderWithProviders(<ChatActivity events={events} isLive={true} totalSteps={5} />);
    expect(screen.getByTestId("chat-activity-live-status")).toBeInTheDocument();
    expect(screen.getByText(/Thinking/)).toBeInTheDocument();
    expect(screen.queryByText("0/5")).not.toBeInTheDocument();
  });

  it("debug surface keeps the classic processing bar while live", () => {
    const events: PipelineEvent[] = [
      {
        type: "task_start",
        taskType: "ai.labs.rules",
        taskId: "1",
        index: 0,
        timestamp: Date.now(),
      },
    ];

    renderWithProviders(<ChatActivity events={events} isLive={true} totalSteps={5} showInternalSteps />);
    expect(screen.getByText(/Processing…/)).toBeInTheDocument();
    expect(screen.getByText("0/5")).toBeInTheDocument();
  });

  it("renders completed state with total steps, duration and tool calls count", () => {
    const events: PipelineEvent[] = [
      {
        type: "task_start",
        taskType: "ai.labs.rules",
        taskId: "1",
        index: 0,
        timestamp: Date.now(),
      },
      {
        type: "task_complete",
        taskType: "ai.labs.rules",
        taskId: "1",
        index: 0,
        durationMs: 150,
        timestamp: Date.now(),
      },
      {
        type: "task_start",
        taskType: "ai.labs.llm",
        taskId: "2",
        index: 1,
        timestamp: Date.now(),
      },
      {
        type: "task_complete",
        taskType: "ai.labs.llm",
        taskId: "2",
        index: 1,
        durationMs: 850,
        timestamp: Date.now(),
        toolTrace: [
          { type: "tool_call", tool: "weather", arguments: '{"city":"Vienna"}' },
          { type: "tool_result", tool: "weather", result: '{"temp":20}' },
        ],
      },
    ];

    renderWithProviders(<ChatActivity events={events} isLive={false} />);

    // End-user resting header leads with what the agent DID: tool calls, not
    // the pipeline's internal step count.
    expect(screen.getByText(/1 tool call\b/)).toBeInTheDocument();
    expect(screen.getByText("1.0s")).toBeInTheDocument(); // 150ms + 850ms = 1000ms = 1.0s
    expect(screen.queryByText(/\b2 steps/)).not.toBeInTheDocument();
  });

  it("toggles expanded state on click", async () => {
    const user = userEvent.setup();
    const events: PipelineEvent[] = [
      {
        type: "task_complete",
        taskType: "ai.labs.rules",
        taskId: "1",
        index: 0,
        durationMs: 5,
        timestamp: Date.now(),
      },
    ];

    renderWithProviders(<ChatActivity events={events} isLive={false} />);
    const toggle = screen.getByTestId("chat-activity-toggle");

    // Initially collapsed since isLive is false
    expect(toggle).toHaveAttribute("aria-expanded", "false");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("renders details and supports tool tracing, expansion, and copying details", async () => {
    // The task-row shell (type icon, label, per-task badge) is a DEBUG
    // affordance now — end-user resting mode gets the flat tool list instead
    // (tested below).
    const user = userEvent.setup();
    const events: PipelineEvent[] = [
      {
        type: "task_complete",
        taskType: "ai.labs.llm",
        taskId: "2",
        index: 1,
        durationMs: 50,
        timestamp: Date.now(),
        toolTrace: [
          { type: "tool_call", tool: "calculator", arguments: "2+2" },
          { type: "tool_result", tool: "calculator", result: "4" },
        ],
      },
      {
        // Unmatched/raw task event
        type: "task_start",
        taskType: "ai.labs.unknown",
        taskId: "3",
        index: 2,
        timestamp: Date.now(),
      },
    ];

    renderWithProviders(<ChatActivity events={events} isLive={false} showInternalSteps />);

    // Auto-expands because the toolTrace makes toolCallCount > 0 — the
    // expansion effect, not liveness, drives it
    expect(screen.getByText("llm")).toBeInTheDocument();
    expect(screen.getByText("unknown")).toBeInTheDocument();

    // Weather tool count badge
    const toolBadge = screen.getByRole("button", { name: "1" });
    expect(toolBadge).toBeInTheDocument();

    // Tool calls list is hidden until we click the badge
    expect(screen.queryByTestId("tool-call-row")).not.toBeInTheDocument();
    await user.click(toolBadge);

    const toolRow = screen.getByTestId("tool-call-row");
    expect(toolRow).toBeInTheDocument();
    expect(screen.getByText("calculator")).toBeInTheDocument();
    expect(screen.getByText("(2+2)")).toBeInTheDocument();

    // Click toolRow to expand details
    await user.click(toolRow);
    expect(screen.getByText("Args")).toBeInTheDocument();
    expect(screen.getByText("Result")).toBeInTheDocument();

    // Copy buttons are rendered. Let's find copy buttons
    const copyBtns = screen.getAllByTitle("Copy");
    expect(copyBtns).toHaveLength(2);

    // Define mock right before clicking to avoid userEvent.setup override
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: mockWriteText },
      writable: true,
      configurable: true,
    });

    fireEvent.click(copyBtns[0]!);
    expect(mockWriteText).toHaveBeenCalledWith("2+2");

    fireEvent.click(copyBtns[1]!);
    expect(mockWriteText).toHaveBeenCalledWith("4");
  });
});

/**
 * httpcalls is plumbing, not activity. An OpenAPI-provisioned agent (the
 * Platform Operator) carries one httpcalls workflow step per endpoint group, so
 * before this filter its every turn opened with dozens of identical unnamed
 * rows — "44 steps" for a greeting — burying the one row that mattered.
 */
describe("ChatActivity — httpcalls pipeline steps", () => {
  const httpcallsStep = (id: string, extra: Partial<PipelineEvent> = {}): PipelineEvent[] => [
    { type: "task_start", taskType: "ai.labs.httpcalls", taskId: id, index: 0, timestamp: Date.now() },
    { type: "task_complete", taskType: "ai.labs.httpcalls", taskId: id, index: 0, timestamp: Date.now(), ...extra },
  ];

  it("hides bare httpcalls steps in end-user mode", () => {
    renderWithProviders(
      <ChatActivity
        events={[...httpcallsStep("h1"), ...httpcallsStep("h2")]}
        isLive={false}
        showInternalSteps={false}
      />,
    );
    expect(screen.queryByText("httpcalls")).not.toBeInTheDocument();
  });

  it("still shows an httpcalls step that failed", () => {
    const events: PipelineEvent[] = [
      { type: "task_start", taskType: "ai.labs.httpcalls", taskId: "h1", index: 0, timestamp: Date.now() },
      { type: "task_failed", taskType: "ai.labs.httpcalls", taskId: "h1", index: 0, timestamp: Date.now() },
    ];
    renderWithProviders(<ChatActivity events={events} isLive={false} showInternalSteps={false} />);
    expect(screen.getByText("httpcalls")).toBeInTheDocument();
  });

  it("keeps them all in debug mode", () => {
    renderWithProviders(
      <ChatActivity events={httpcallsStep("h1")} isLive={false} showInternalSteps={true} />,
    );
    expect(screen.getByText("httpcalls")).toBeInTheDocument();
  });
});

/**
 * "unknown" is the backend classifier's shrug, not a diagnosis. Rendered alone
 * as a badge it looked like the error itself — an admin saw "UNKNOWN" and
 * nothing else, and had to go to the server log to learn the turn failed.
 */
describe("ChatActivity — failed step detail", () => {
  const failed = (extra: Partial<PipelineEvent>): PipelineEvent[] => [
    { type: "task_start", taskType: "ai.labs.langchain", taskId: "l1", index: 0, timestamp: Date.now() },
    { type: "task_failed", taskType: "ai.labs.langchain", taskId: "l1", index: 0, timestamp: Date.now(), ...extra },
  ];

  it("suppresses the meaningless 'unknown' badge but keeps the summary", () => {
    renderWithProviders(
      <ChatActivity
        events={failed({ errorType: "unknown", errorSummary: "temperature is deprecated for this model" })}
        isLive={false}
      />,
    );
    expect(screen.queryByText("unknown")).not.toBeInTheDocument();
    expect(screen.getByText(/temperature is deprecated/)).toBeInTheDocument();
  });

  it("points at the server log when the failure arrives with no detail at all", () => {
    renderWithProviders(<ChatActivity events={failed({})} isLive={false} />);
    expect(screen.getByTestId("task-error-detail")).toHaveTextContent(/server log has the full error/i);
  });

  it("still shows a REAL classification as a badge", () => {
    renderWithProviders(
      <ChatActivity events={failed({ errorType: "timeout", errorSummary: "took too long" })} isLive={false} />,
    );
    expect(screen.getByText("timeout")).toBeInTheDocument();
  });
});

/**
 * Filtering the rows while summarising the unfiltered set re-created the exact
 * complaint the filter fixed: one visible row under a header still boasting
 * "46 steps". The summary must describe what the user can see — except the
 * duration, which reports the TURN's real latency and deliberately includes
 * hidden plumbing time.
 */
describe("ChatActivity — summary metrics follow the filtered list", () => {
  it("hidden httpcalls steps do not inflate the step count", () => {
    const events: PipelineEvent[] = [];
    for (let i = 0; i < 45; i++) {
      events.push(
        { type: "task_start", taskType: "ai.labs.httpcalls", taskId: `h${i}`, index: i, timestamp: Date.now() },
        { type: "task_complete", taskType: "ai.labs.httpcalls", taskId: `h${i}`, index: i, timestamp: Date.now(), durationMs: 2 },
      );
    }
    events.push(
      { type: "task_start", taskType: "ai.labs.langchain", taskId: "l1", index: 45, timestamp: Date.now() },
      {
        type: "task_complete", taskType: "ai.labs.langchain", taskId: "l1", index: 45, timestamp: Date.now(),
        durationMs: 900,
        toolTrace: [{ type: "tool_call", tool: "readAgentDescriptors" }],
      },
    );

    renderWithProviders(<ChatActivity events={events} isLive={false} showInternalSteps={false} />);

    // With a tool call in the turn, the header leads with it — the plumbing
    // step count ("46 steps", or even "1 step") never surfaces.
    expect(screen.getByText(/\b1 tool call\b/)).toBeInTheDocument();
    expect(screen.queryByText(/\b46 steps/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\b1 step\b/)).not.toBeInTheDocument();
    // The duration is the turn's, not the visible row's: 45×2ms + 900ms.
    expect(screen.getByText("990ms")).toBeInTheDocument();
  });

  it("debug mode still reports every step", () => {
    const events: PipelineEvent[] = [
      { type: "task_start", taskType: "ai.labs.httpcalls", taskId: "h1", index: 0, timestamp: Date.now() },
      { type: "task_complete", taskType: "ai.labs.httpcalls", taskId: "h1", index: 0, timestamp: Date.now() },
      { type: "task_start", taskType: "ai.labs.parser", taskId: "p1", index: 1, timestamp: Date.now() },
      { type: "task_complete", taskType: "ai.labs.parser", taskId: "p1", index: 1, timestamp: Date.now() },
    ];
    renderWithProviders(<ChatActivity events={events} isLive={false} showInternalSteps={true} />);
    expect(screen.getByText(/\b2 steps/)).toBeInTheDocument();
  });
});

/**
 * End-user live mode is one status line: what the agent is doing right now.
 * The wall of internal step rows (dozens of identical httpcalls entries, some
 * spinning forever when their completion never pairs up) is debug-surface-only.
 */
describe("ChatActivity — end-user live status line", () => {
  const start = (taskType: string, index: number): PipelineEvent => ({
    type: "task_start",
    taskType,
    taskId: String(index),
    index,
    timestamp: Date.now(),
  });

  it("shows Thinking before any tool call, and never the step rows", () => {
    const events: PipelineEvent[] = [
      start("ai.labs.httpcalls", 0),
      { ...start("ai.labs.httpcalls", 0), type: "task_complete", durationMs: 1 },
      start("ai.labs.httpcalls", 1),
    ];

    renderWithProviders(<ChatActivity events={events} isLive={true} />);

    expect(screen.getByTestId("chat-activity-live-status")).toBeInTheDocument();
    expect(screen.getByText(/Thinking/)).toBeInTheDocument();
    // No row list, no expander — the httpcalls wall must not render live.
    expect(screen.queryByTestId("chat-activity-toggle")).not.toBeInTheDocument();
    expect(screen.queryByText("httpCalls")).not.toBeInTheDocument();
  });

  it("names the tool currently in use once a toolTrace event arrives", () => {
    const events: PipelineEvent[] = [
      start("ai.labs.httpcalls", 0),
      {
        ...start("ai.labs.httpcalls", 0),
        type: "task_complete",
        durationMs: 2,
        toolTrace: [
          { type: "tool_call", tool: "readAgent", arguments: "{}" },
          { type: "tool_result", tool: "readAgent", result: "ok" },
        ],
      },
      start("ai.labs.httpcalls", 1),
    ];

    renderWithProviders(<ChatActivity events={events} isLive={true} />);

    expect(screen.getByText(/readAgent/)).toBeInTheDocument();
    expect(screen.getByText(/1 tool call/)).toBeInTheDocument();
  });

  it("a failed task breaks through the minimal line to the full view", () => {
    const events: PipelineEvent[] = [
      start("ai.labs.langchain", 0),
      {
        ...start("ai.labs.langchain", 0),
        type: "task_failed",
        errorType: "timeout",
        errorSummary: "provider timed out",
      },
    ];

    renderWithProviders(<ChatActivity events={events} isLive={true} />);

    expect(screen.queryByTestId("chat-activity-live-status")).not.toBeInTheDocument();
    expect(screen.getByTestId("chat-activity-toggle")).toBeInTheDocument();
  });
});

/**
 * The runtime emits camelCase ids ("httpCalls"); the filter set is lowercase.
 * An exact has() filtered NOTHING in production — the "41 steps" wall of
 * spinner rows — while these tests' lowercase fixtures kept passing.
 */
describe("ChatActivity — camelCase runtime task ids", () => {
  it.each(["httpCalls", "mcpCalls", "properties"])(
    "filters the real '%s' casing at rest — nothing meaningful, nothing shown",
    (taskType) => {
      const events: PipelineEvent[] = [
        { type: "task_start", taskType, taskId: "1", index: 0, timestamp: Date.now() },
        { type: "task_complete", taskType, taskId: "1", index: 0, durationMs: 1, timestamp: Date.now() },
        // Unpaired start — the forever-spinner shape from the screenshot.
        { type: "task_start", taskType, taskId: "2", index: 1, timestamp: Date.now() },
      ];

      renderWithProviders(<ChatActivity events={events} isLive={false} />);

      expect(screen.queryByTestId("chat-activity")).not.toBeInTheDocument();
    },
  );

  it("keeps a camelCase turn that actually made tool calls, led by the tool-call count", () => {
    const events: PipelineEvent[] = [
      { type: "task_start", taskType: "httpCalls", taskId: "1", index: 0, timestamp: Date.now() },
      {
        type: "task_complete", taskType: "httpCalls", taskId: "1", index: 0, durationMs: 5, timestamp: Date.now(),
        toolTrace: [{ type: "tool_call", tool: "readAgent", arguments: "{}" }],
      },
      { type: "task_start", taskType: "httpCalls", taskId: "2", index: 1, timestamp: Date.now() },
      { type: "task_complete", taskType: "httpCalls", taskId: "2", index: 1, durationMs: 1, timestamp: Date.now() },
    ];

    renderWithProviders(<ChatActivity events={events} isLive={false} />);

    expect(screen.getByText(/\b1 tool call\b/)).toBeInTheDocument();
    expect(screen.queryByText(/steps?\b/)).not.toBeInTheDocument();
  });
});

/**
 * The end-user resting view is a flat record of what the agent DID. The old
 * shape — "1 step · 59.2s · 8 tool calls" over a `langchain` task row that had
 * to be expanded AGAIN to reach the calls — was three layers of pipeline
 * plumbing around the only content that mattered.
 */
describe("ChatActivity — end-user resting flat tool list", () => {
  const turn = (): PipelineEvent[] => [
    { type: "task_start", taskType: "langchain", taskId: "l1", index: 0, timestamp: Date.now() },
    {
      type: "task_complete", taskType: "langchain", taskId: "l1", index: 0, durationMs: 59_200, timestamp: Date.now(),
      toolTrace: [
        { type: "tool_call", tool: "readAgentDescriptors", arguments: "{}" },
        { type: "tool_result", tool: "readAgentDescriptors", result: "[]" },
        { type: "tool_call", tool: "readGroups", arguments: "{}" },
        { type: "tool_result", tool: "readGroups", result: "[]" },
      ],
    },
  ];

  it("stays collapsed by default — the pill itself is the summary", () => {
    renderWithProviders(<ChatActivity events={turn()} isLive={false} />);
    expect(screen.getByTestId("chat-activity-toggle")).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText(/\b2 tool calls\b/)).toBeInTheDocument();
    expect(screen.getByText("59.2s")).toBeInTheDocument();
  });

  it("expands to the flat tool-call list with no task-row shell around it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ChatActivity events={turn()} isLive={false} />);

    await user.click(screen.getByTestId("chat-activity-toggle"));

    // The calls themselves, directly — no "langchain" row to expand again.
    expect(screen.getAllByTestId("tool-call-row")).toHaveLength(2);
    expect(screen.getByText("readAgentDescriptors")).toBeInTheDocument();
    expect(screen.getByText("readGroups")).toBeInTheDocument();
    expect(screen.queryByText("langchain")).not.toBeInTheDocument();
  });

  it("a failed step still surfaces inside the expanded flat view", async () => {
    const user = userEvent.setup();
    const events: PipelineEvent[] = [
      ...turn(),
      { type: "task_start", taskType: "output", taskId: "o1", index: 1, timestamp: Date.now() },
      {
        type: "task_failed", taskType: "output", taskId: "o1", index: 1, timestamp: Date.now(),
        errorType: "timeout", errorSummary: "provider timed out",
      },
    ];
    renderWithProviders(<ChatActivity events={events} isLive={false} />);

    // Errors auto-expand — they are actionable.
    expect(screen.getByTestId("chat-activity-toggle")).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(/provider timed out/)).toBeInTheDocument();
    expect(screen.getAllByTestId("tool-call-row")).toHaveLength(2);
    // Collapse/expand still works with both present (the detail panel is
    // hidden via CSS animation, so assert the toggle state, not unmounting).
    await user.click(screen.getByTestId("chat-activity-toggle"));
    expect(screen.getByTestId("chat-activity-toggle")).toHaveAttribute("aria-expanded", "false");
  });
});

/**
 * Live `tool_call` SSE events are the immediate signal — the per-task
 * toolTrace only arrives at task_complete, which for a single long tool loop
 * is the end of the turn. Without the live list the status line said
 * "Thinking…" through an entire tool-using conversation.
 */
describe("ChatActivity — live tool_call events", () => {
  const start = (taskType: string, index: number): PipelineEvent => ({
    type: "task_start",
    taskType,
    taskId: String(index),
    index,
    timestamp: Date.now(),
  });

  it("names the tool from liveToolCalls before any toolTrace exists", () => {
    renderWithProviders(
      <ChatActivity
        events={[start("langchain", 0)]}
        isLive={true}
        liveToolCalls={["readAgentDescriptors"]}
      />,
    );

    expect(screen.getByText(/readAgentDescriptors/)).toBeInTheDocument();
    expect(screen.getByText(/1 tool call\b/)).toBeInTheDocument();
  });

  it("prefers the newest live call over a stale completed-task trace", () => {
    const events: PipelineEvent[] = [
      start("httpCalls", 0),
      {
        ...start("httpCalls", 0),
        type: "task_complete",
        durationMs: 2,
        toolTrace: [{ type: "tool_call", tool: "oldTool", arguments: "{}" }],
      },
    ];

    renderWithProviders(
      <ChatActivity events={events} isLive={true} liveToolCalls={["oldTool", "newTool"]} />,
    );

    expect(screen.getByText(/newTool/)).toBeInTheDocument();
    expect(screen.queryByText(/Using oldTool/)).not.toBeInTheDocument();
    // Count comes from the live list (2), not the lagging trace (1).
    expect(screen.getByText(/2 tool calls\b/)).toBeInTheDocument();
  });

  it("renders the status line from live calls alone — before any task event arrives", () => {
    renderWithProviders(<ChatActivity events={[]} isLive={true} liveToolCalls={["readGroups"]} />);

    // events=[] used to be an early-return null; the live list must count as
    // activity or the panel falls back to the bare dots indicator.
    expect(screen.getByTestId("chat-activity-live-status")).toBeInTheDocument();
    expect(screen.getByText(/readGroups/)).toBeInTheDocument();
  });

  it("expands to the full running list of calls, newest still spinning", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ChatActivity
        events={[]}
        isLive={true}
        liveToolCalls={["readAgentDescriptors", "readGroups", "readConversations"]}
      />,
    );

    expect(screen.queryByTestId("chat-activity-live-list")).not.toBeInTheDocument();
    await user.click(screen.getByTestId("chat-activity-live-status"));

    const list = screen.getByTestId("chat-activity-live-list");
    expect(list).toHaveTextContent("readAgentDescriptors");
    expect(list).toHaveTextContent("readGroups");
    expect(list).toHaveTextContent("readConversations");
    // Collapses again on a second click.
    await user.click(screen.getByTestId("chat-activity-live-status"));
    expect(screen.queryByTestId("chat-activity-live-list")).not.toBeInTheDocument();
  });

  it("a pill with no calls yet is disabled — no focusable no-op, no phantom disclosure", () => {
    renderWithProviders(<ChatActivity events={[start("langchain", 0)]} isLive={true} liveToolCalls={[]} />);

    const pill = screen.getByTestId("chat-activity-live-status");
    expect(pill).toBeDisabled();
    expect(pill).not.toHaveAttribute("aria-expanded");
    expect(screen.queryByTestId("chat-activity-live-list")).not.toBeInTheDocument();
  });
});
