import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TaskCascadeSection } from "../../task-cascade-section";
import type { LlmTask, ModelCascadeConfig } from "../../types";

// SecretKeyPicker pulls vault data via react-query; renderWithProviders wires
// the client, and with no backend the hooks simply resolve to empty/undefined.

/** Stateful harness so interactions (toggles, add-step) re-render. */
function Harness({ initial, onChangeSpy }: { initial: LlmTask; onChangeSpy?: (t: LlmTask) => void }) {
  const [task, setTask] = useState<LlmTask>(initial);
  return (
    <TaskCascadeSection
      task={task}
      onChange={(t) => {
        setTask(t);
        onChangeSpy?.(t);
      }}
    />
  );
}

function taskWith(cascade: Partial<ModelCascadeConfig>, type = "openai"): LlmTask {
  return {
    type,
    parameters: { systemMessage: "hi", apiKey: "${vault:openai-key}" },
    modelCascade: { enabled: true, ...cascade },
  } as LlmTask;
}

describe("TaskCascadeSection", () => {
  it("collapses when disabled and shows only the enable toggle once expanded", async () => {
    renderWithProviders(<Harness initial={{ type: "openai", parameters: {} } as LlmTask} />);
    // Collapsed while disabled — expand the section header.
    await userEvent.click(screen.getByRole("button", { name: /model cascade/i }));
    expect(screen.getByTestId("cascade-enable")).not.toBeChecked();
    expect(screen.queryByTestId("cascade-ceilings")).not.toBeInTheDocument();
  });

  it("shows ceilings and the return-best toggle when enabled", () => {
    renderWithProviders(
      <Harness initial={taskWith({ steps: [{ confidenceThreshold: null }] })} />,
    );
    expect(screen.getByTestId("cascade-ceilings")).toBeInTheDocument();
    expect(screen.getByTestId("cascade-return-best")).toBeInTheDocument();
  });

  it("shows the judge-model editor only for the judge_model strategy", () => {
    renderWithProviders(
      <Harness
        initial={taskWith({
          evaluationStrategy: "judge_model",
          judgeModel: { type: "openai", parameters: { model: "gpt-4o-mini", apiKey: "${vault:k}" } },
          steps: [{ confidenceThreshold: null }],
        })}
      />,
    );
    expect(screen.getByTestId("cascade-judge-model")).toBeInTheDocument();
    expect(screen.queryByTestId("cascade-heuristic")).not.toBeInTheDocument();
  });

  it("shows the heuristic editor only for the heuristic strategy", () => {
    renderWithProviders(
      <Harness
        initial={taskWith({ evaluationStrategy: "heuristic", steps: [{ confidenceThreshold: null }] })}
      />,
    );
    expect(screen.getByTestId("cascade-heuristic")).toBeInTheDocument();
    expect(screen.queryByTestId("cascade-judge-model")).not.toBeInTheDocument();
  });

  it("warns when a cross-provider step lacks its own API key", () => {
    renderWithProviders(
      <Harness
        initial={taskWith({
          steps: [
            { type: "anthropic", parameters: { model: "claude-sonnet-4" }, confidenceThreshold: 0.7 },
            { type: "openai", confidenceThreshold: null },
          ],
        })}
      />,
    );
    expect(screen.getByTestId("cascade-issue-STEP_CROSS_PROVIDER_NO_APIKEY")).toBeInTheDocument();
  });

  it("surfaces a hard error for a negative cost ceiling", () => {
    renderWithProviders(
      <Harness initial={taskWith({ maxCostPerRun: -1, steps: [{ confidenceThreshold: null }] })} />,
    );
    expect(screen.getByTestId("cascade-issue-MAX_COST_NEGATIVE")).toBeInTheDocument();
  });

  it("adds a step when the add button is clicked", async () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={taskWith({ steps: [] })} onChangeSpy={spy} />);
    await userEvent.click(screen.getByTestId("add-cascade-step"));
    expect(screen.getByTestId("cascade-step-0")).toBeInTheDocument();
    const last = spy.mock.calls.at(-1)![0] as LlmTask;
    expect(last.modelCascade?.steps).toHaveLength(1);
  });

  it("toggles return-best-across-steps", async () => {
    const spy = vi.fn();
    renderWithProviders(
      <Harness initial={taskWith({ steps: [{ confidenceThreshold: null }] })} onChangeSpy={spy} />,
    );
    await userEvent.click(screen.getByTestId("cascade-return-best"));
    const last = spy.mock.calls.at(-1)![0] as LlmTask;
    expect(last.modelCascade?.returnBestAcrossSteps).toBe(true);
  });
});
