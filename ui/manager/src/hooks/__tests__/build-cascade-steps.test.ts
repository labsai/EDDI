import { describe, it, expect } from "vitest";
import { buildCascadeSteps, type PipelineEvent } from "@/hooks/use-debug-events";

const T = 1000;

describe("buildCascadeSteps", () => {
  it("returns an empty list when there are no cascade events", () => {
    const events: PipelineEvent[] = [
      { type: "task_start", taskId: "1", taskType: "x", index: 0, timestamp: T },
    ];
    expect(buildCascadeSteps(events)).toEqual([]);
  });

  it("folds step-start and escalation events into ordered steps", () => {
    const events: PipelineEvent[] = [
      { type: "cascade_step_start", taskId: "c", taskType: "openai", index: 0, stepIndex: 0, modelName: "gpt-4o-mini", totalSteps: 2, timestamp: T },
      { type: "cascade_escalation", taskId: "c", taskType: "cascade", index: 0, fromStep: 0, toStep: 1, confidence: 0.6, threshold: 0.7, reason: "low_confidence", timestamp: T + 1 },
      { type: "cascade_step_start", taskId: "c", taskType: "openai", index: 1, stepIndex: 1, modelName: "gpt-4o", totalSteps: 2, timestamp: T + 2 },
    ];
    const steps = buildCascadeSteps(events);
    expect(steps).toHaveLength(2);
    expect(steps[0]).toMatchObject({ stepIndex: 0, modelName: "gpt-4o-mini" });
    // Escalation info attaches to the SOURCE step (the one that was rejected).
    expect(steps[0]!.escalation).toMatchObject({
      toStep: 1,
      confidence: 0.6,
      threshold: 0.7,
      reason: "low_confidence",
    });
    expect(steps[1]).toMatchObject({ stepIndex: 1, modelName: "gpt-4o" });
    expect(steps[1]!.escalation).toBeUndefined();
  });

  it("sorts steps by index regardless of event order", () => {
    const events: PipelineEvent[] = [
      { type: "cascade_step_start", taskId: "c", taskType: "openai", index: 2, stepIndex: 2, modelName: "c", timestamp: T },
      { type: "cascade_step_start", taskId: "c", taskType: "openai", index: 0, stepIndex: 0, modelName: "a", timestamp: T },
    ];
    expect(buildCascadeSteps(events).map((s) => s.stepIndex)).toEqual([0, 2]);
  });
});
