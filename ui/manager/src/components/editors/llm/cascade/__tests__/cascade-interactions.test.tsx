import { describe, it, expect } from "vitest";
import { useState } from "react";
import { screen, fireEvent, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { TaskCascadeSection } from "../../task-cascade-section";
import type { LlmTask, ModelCascadeConfig } from "../../types";

/** Stateful harness so every onChange re-renders with the new task. */
function Harness({ initial }: { initial: LlmTask }) {
  const [task, setTask] = useState<LlmTask>(initial);
  return <TaskCascadeSection task={task} onChange={setTask} />;
}
function mk(cascade: Partial<ModelCascadeConfig>): LlmTask {
  return {
    type: "openai",
    parameters: { systemMessage: "hi", apiKey: "${vault:openai-key}" },
    modelCascade: { enabled: true, ...cascade },
  } as LlmTask;
}

describe("cascade editor — interactions drive the handlers", () => {
  it("edits strategy, evaluation, toggles, ceilings, judge model, and step controls", () => {
    renderWithProviders(
      <Harness
        initial={mk({
          evaluationStrategy: "judge_model",
          judgeModel: { type: "openai", parameters: { model: "gpt-4o-mini", apiKey: "${vault:k}" } },
          steps: [
            { type: "openai", parameters: { model: "gpt-4o-mini" }, confidenceThreshold: 0.7, timeoutMs: 10000 },
            { type: "openai", parameters: { model: "gpt-4o" }, confidenceThreshold: null, timeoutMs: 30000 },
          ],
        })}
      />,
    );

    // Strategy + evaluation selects
    fireEvent.change(screen.getByDisplayValue("Sequential Escalation"), { target: { value: "parallel" } });
    // Toggles
    fireEvent.click(screen.getByTestId("cascade-return-best"));

    // Ceilings — all four numeric handlers
    fireEvent.change(screen.getByTestId("cascade-max-duration"), { target: { value: "45000" } });
    fireEvent.change(screen.getByTestId("cascade-max-cost"), { target: { value: "0.05" } });
    fireEvent.change(screen.getByTestId("cascade-input-price"), { target: { value: "0.15" } });
    fireEvent.change(screen.getByTestId("cascade-output-price"), { target: { value: "0.6" } });

    // Judge model — type select + model name
    fireEvent.change(screen.getByTestId("cascade-judge-type"), { target: { value: "anthropic" } });
    fireEvent.change(screen.getByTestId("cascade-judge-model-name"), { target: { value: "claude-haiku" } });

    // Step 0 — model, confidence, timeout
    const step0 = screen.getByTestId("cascade-step-0");
    fireEvent.change(within(step0).getByPlaceholderText("e.g. claude-sonnet-5"), { target: { value: "gpt-4o-nano" } });
    fireEvent.change(within(step0).getByPlaceholderText("empty = always accept"), { target: { value: "0.85" } });
    fireEvent.change(within(step0).getByPlaceholderText("30000"), { target: { value: "8000" } });
    // Provider select (first select inside the step card)
    fireEvent.change(within(step0).getByRole("combobox"), { target: { value: "anthropic" } });

    // Open the step's advanced section → pricing + add a parameter
    fireEvent.click(screen.getByTestId("cascade-step-advanced-toggle-0"));
    const priceInputs = within(screen.getByTestId("cascade-step-0")).getAllByPlaceholderText("cascade default");
    fireEvent.change(priceInputs[0]!, { target: { value: "0.1" } });
    fireEvent.change(priceInputs[1]!, { target: { value: "0.2" } });
    fireEvent.click(within(screen.getByTestId("cascade-step-0")).getByText("Add parameter"));

    // Reorder + remove still work
    fireEvent.click(screen.getAllByTitle("Move down")[0]!);
    fireEvent.click(screen.getByTestId("add-cascade-step"));

    // Section is still intact after all the edits
    expect(screen.getByTestId("cascade-section")).toBeInTheDocument();
  });

  it("edits the heuristic tuning panel", () => {
    renderWithProviders(
      <Harness initial={mk({ evaluationStrategy: "heuristic", steps: [{ confidenceThreshold: null }] })} />,
    );

    // Expand the collapsible "Heuristic tuning" section
    fireEvent.click(screen.getByRole("button", { name: /heuristic tuning/i }));

    // Add a hedging phrase and a refusal phrase
    const phraseInputs = screen.getAllByPlaceholderText("add a phrase…");
    fireEvent.change(phraseInputs[0]!, { target: { value: "maybe" } });
    fireEvent.keyDown(phraseInputs[0]!, { key: "Enter" });
    fireEvent.change(phraseInputs[1]!, { target: { value: "I cannot" } });
    fireEvent.click(screen.getAllByLabelText("Add")[1]!);

    // Numeric overrides
    fireEvent.change(screen.getByPlaceholderText("20"), { target: { value: "30" } });
    fireEvent.change(screen.getByPlaceholderText("0.3"), { target: { value: "0.35" } });
    fireEvent.change(screen.getByPlaceholderText("0.2"), { target: { value: "0.25" } });
    fireEvent.change(screen.getByPlaceholderText("0.4"), { target: { value: "0.45" } });
    fireEvent.change(screen.getByPlaceholderText("0.8"), { target: { value: "0.85" } });

    expect(screen.getByTestId("cascade-heuristic")).toBeInTheDocument();
    // The added hedging phrase renders as a chip
    expect(screen.getByText("maybe")).toBeInTheDocument();
  });
});
