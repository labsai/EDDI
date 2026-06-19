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

    renderWithProviders(<ChatActivity events={events} isLive={true} totalSteps={5} />);
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

    expect(screen.getByText(/2 steps/)).toBeInTheDocument();
    expect(screen.getByText("1.0s")).toBeInTheDocument(); // 150ms + 850ms = 1000ms = 1.0s
    expect(screen.getByText(/1 tool calls/)).toBeInTheDocument();
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

    renderWithProviders(<ChatActivity events={events} isLive={true} />);

    // Since isLive is true, it should start expanded
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
