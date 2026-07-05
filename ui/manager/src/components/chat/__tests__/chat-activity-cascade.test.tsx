import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ChatActivity } from "../chat-activity";
import type { PipelineEvent } from "@/hooks/use-debug-events";

const T = Date.now();

function cascadeEvents(): PipelineEvent[] {
  return [
    { type: "cascade_step_start", taskId: "cascade", taskType: "openai", index: 0, stepIndex: 0, modelName: "gpt-4o-mini", totalSteps: 2, timestamp: T },
    { type: "cascade_escalation", taskId: "cascade", taskType: "cascade", index: 0, fromStep: 0, toStep: 1, confidence: 0.62, threshold: 0.7, reason: "low_confidence", timestamp: T + 1 },
    { type: "cascade_step_start", taskId: "cascade", taskType: "openai", index: 1, stepIndex: 1, modelName: "gpt-4o", totalSteps: 2, timestamp: T + 2 },
  ];
}

describe("ChatActivity — model cascade", () => {
  it("renders the cascade trace with model tiers and an escalation reason", () => {
    renderWithProviders(<ChatActivity events={cascadeEvents()} isLive={true} />);
    expect(screen.getByTestId("cascade-trace")).toBeInTheDocument();
    expect(screen.getByTestId("cascade-trace-step-0")).toBeInTheDocument();
    expect(screen.getByTestId("cascade-trace-step-1")).toBeInTheDocument();
    expect(screen.getByText("gpt-4o-mini")).toBeInTheDocument();
    expect(screen.getByText("gpt-4o")).toBeInTheDocument();
    expect(screen.getByText(/low confidence/)).toBeInTheDocument();
    // The final step is clean, so it carries the "accepted" marker.
    expect(screen.getByText("accepted")).toBeInTheDocument();
  });

  it("suppresses the accepted marker on a last step that itself escalated (partial live trace)", () => {
    // The escalation is recorded but the destination step's start hasn't streamed
    // in yet, so the escalated step is momentarily the last rendered row.
    const events: PipelineEvent[] = [
      { type: "cascade_step_start", taskId: "cascade", taskType: "openai", index: 0, stepIndex: 0, modelName: "gpt-4o-mini", totalSteps: 2, timestamp: T },
      { type: "cascade_escalation", taskId: "cascade", taskType: "cascade", index: 0, fromStep: 0, toStep: 1, confidence: 0.62, threshold: 0.7, reason: "low_confidence", timestamp: T + 1 },
    ];
    renderWithProviders(<ChatActivity events={events} isLive={true} />);
    expect(screen.getByTestId("cascade-trace-step-0")).toBeInTheDocument();
    expect(screen.getByText(/low confidence/)).toBeInTheDocument();
    // Escalated + accepted on the same step would be contradictory — no marker.
    expect(screen.queryByText("accepted")).not.toBeInTheDocument();
  });

  it("renders no cascade trace when there are no cascade events", () => {
    const events: PipelineEvent[] = [
      { type: "task_start", taskId: "1", taskType: "ai.labs.rules", index: 0, timestamp: T },
      { type: "task_complete", taskId: "1", taskType: "ai.labs.rules", index: 0, durationMs: 5, timestamp: T + 5 },
    ];
    renderWithProviders(<ChatActivity events={events} isLive={true} />);
    expect(screen.queryByTestId("cascade-trace")).not.toBeInTheDocument();
  });
});
