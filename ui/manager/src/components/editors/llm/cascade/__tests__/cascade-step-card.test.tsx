import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CascadeStepCard } from "@/components/editors/llm/cascade/cascade-step-card";
import type { CascadeStep } from "@/components/editors/llm/types";

function renderStep(step: CascadeStep, taskType = "openai", taskParameters?: Record<string, string>) {
  const onChange = vi.fn();
  renderWithProviders(
    <CascadeStepCard
      step={step}
      index={0}
      totalSteps={1}
      taskType={taskType}
      taskParameters={taskParameters}
      issues={[]}
      onChange={onChange}
      onMoveUp={() => {}}
      onMoveDown={() => {}}
      onRemove={() => {}}
    />,
  );
  return onChange;
}

const modelField = () => screen.getByTestId("cascade-step-model-0");
const lastParams = (onChange: ReturnType<typeof vi.fn>) =>
  (onChange.mock.lastCall![0] as Partial<CascadeStep>).parameters;

describe("CascadeStepCard model key", () => {
  it.each([
    ["openai", "modelName"],
    ["anthropic", "modelName"],
    ["deepseek", "modelName"],
    ["ollama", "model"],
    ["bedrock", "modelId"],
    ["azure-openai", "deploymentName"],
  ])("%s: reads and writes %s", async (type, key) => {
    const user = userEvent.setup();
    const onChange = renderStep({ type, parameters: { [key]: "m" } });
    expect(modelField()).toHaveValue("m");
    expect(modelField()).toHaveAttribute("data-model-key", key);
    await user.type(modelField(), "x");
    expect(lastParams(onChange)).toEqual({ [key]: "mx" });
  });

  it("uses the task's provider for a step without its own type", async () => {
    const user = userEvent.setup();
    const onChange = renderStep({ parameters: {} }, "azure-openai");
    await user.type(modelField(), "d");
    expect(lastParams(onChange)).toEqual({ deploymentName: "d" });
  });

  it("shows a legacy `model` value and moves it to the provider's key on the first edit only", async () => {
    const user = userEvent.setup();
    const onChange = renderStep({ type: "openai", parameters: { model: "gpt-4o", seed: "1" } });
    expect(modelField()).toHaveValue("gpt-4o");
    // Rendering alone rewrites nothing.
    expect(onChange).not.toHaveBeenCalled();
    await user.type(modelField(), "x");
    expect(lastParams(onChange)).toEqual({ modelName: "gpt-4ox", seed: "1" });
  });

  it("keeps a stray `model` visible in the grid once the provider's own key is set", async () => {
    const user = userEvent.setup();
    renderStep({ type: "openai", parameters: { modelName: "gpt-4o", model: "old" } });
    expect(modelField()).toHaveValue("gpt-4o");
    await user.click(screen.getByTestId("cascade-step-advanced-toggle-0"));
    expect(screen.getByDisplayValue("model")).toBeInTheDocument();
    expect(screen.queryByDisplayValue("modelName")).not.toBeInTheDocument();
  });

  it("hides the model key and a legacy model from the extra-parameters grid", async () => {
    const user = userEvent.setup();
    renderStep({ type: "azure-openai", parameters: { deploymentName: "prod", timeout: "30" } });
    await user.click(screen.getByTestId("cascade-step-advanced-toggle-0"));
    expect(screen.getByDisplayValue("timeout")).toBeInTheDocument();
    expect(screen.queryByDisplayValue("deploymentName")).not.toBeInTheDocument();
  });

  it("carries the model to the new provider's key when the provider changes", async () => {
    const user = userEvent.setup();
    const onChange = renderStep({ type: "openai", parameters: { modelName: "llama3", seed: "1" } });
    await user.selectOptions(screen.getByTestId("cascade-step-type"), "ollama");
    expect(onChange.mock.lastCall![0]).toEqual({ type: "ollama", parameters: { model: "llama3", seed: "1" } });
  });

  it("judges the settings against the model the field shows, including a legacy one", async () => {
    const user = userEvent.setup();
    // The task runs gpt-4o; the step (legacy key) runs gpt-5, which rejects topP.
    renderStep({ type: "openai", parameters: { model: "gpt-5", topP: "0.5" } }, "openai", { modelName: "gpt-4o" });
    await user.click(screen.getByTestId("cascade-step-advanced-toggle-0"));
    expect(screen.getByTestId("cascade-step-0-settings-param-topP")).toHaveAttribute("data-state", "rejected");
  });
});
