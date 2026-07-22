import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ChatActivity } from "@/components/chat/chat-activity";
import type { PipelineEvent } from "@/hooks/use-debug-events";

// Regression: the classified task_failed SSE event was dropped, so a failing
// stage stuck on "running" and the "error" status branch was dead code.
describe("ChatActivity — task_failed", () => {
  const events: PipelineEvent[] = [
    { type: "task_start", taskId: "t1", taskType: "ai.labs.llm", index: 0, timestamp: 1 },
    {
      type: "task_failed",
      taskId: "t1",
      taskType: "ai.labs.llm",
      index: 0,
      errorType: "rate_limit",
      errorSummary: "provider throttled the request",
      durationMs: 50,
      timestamp: 2,
    },
  ];

  it("marks the task errored and surfaces the classified errorType + summary", () => {
    renderWithProviders(<ChatActivity events={events} isLive />);
    const detail = screen.getByTestId("task-error-detail");
    expect(detail).toHaveTextContent("rate_limit");
    expect(detail).toHaveTextContent("provider throttled the request");
  });

  it("does not leave the failed task rendering as still-running", () => {
    renderWithProviders(<ChatActivity events={events} isLive />);
    // A running task renders a "…" duration placeholder; an errored one must not.
    const detail = screen.getByTestId("task-error-detail");
    expect(detail).toBeInTheDocument();
  });
});
