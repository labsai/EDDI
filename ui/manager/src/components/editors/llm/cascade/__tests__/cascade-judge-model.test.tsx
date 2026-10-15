import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CascadeJudgeModelEditor } from "@/components/editors/llm/cascade/cascade-judge-model";
import type { CascadeJudgeModel } from "@/components/editors/llm/types";

function renderJudge(value: CascadeJudgeModel) {
  const onChange = vi.fn();
  renderWithProviders(<CascadeJudgeModelEditor value={value} onChange={onChange} />);
  return onChange;
}

const field = () => screen.getByTestId("cascade-judge-model-name");

describe("CascadeJudgeModelEditor model key", () => {
  it.each([
    ["openai", "modelName"],
    ["anthropic", "modelName"],
    ["ollama", "model"],
    ["gemini-vertex", "modelId"],
    ["azure-openai", "deploymentName"],
  ])("%s: reads and writes %s", async (type, key) => {
    const user = userEvent.setup();
    const onChange = renderJudge({ type, parameters: { [key]: "m", apiKey: "k" } });
    expect(field()).toHaveValue("m");
    expect(field()).toHaveAttribute("data-model-key", key);
    await user.type(field(), "x");
    expect(onChange.mock.lastCall![0]).toEqual({ type, parameters: { [key]: "mx", apiKey: "k" } });
  });

  it("does not offer a self-hosted endpoint, which needs a base URL the judge has no field for", () => {
    renderJudge({ type: "openai", parameters: {} });
    const values = Array.from((screen.getByTestId("cascade-judge-type") as HTMLSelectElement).options).map((o) => o.value);
    expect(values).not.toContain("openai-compatible");
    expect(values).toContain("ollama");
  });

  it("shows a legacy `model` value and moves it on the first edit only", async () => {
    const user = userEvent.setup();
    const onChange = renderJudge({ type: "openai", parameters: { model: "gpt-4o-mini" } });
    expect(field()).toHaveValue("gpt-4o-mini");
    expect(onChange).not.toHaveBeenCalled();
    await user.type(field(), "x");
    expect(onChange.mock.lastCall![0]).toEqual({ type: "openai", parameters: { modelName: "gpt-4o-minix" } });
  });

  it("carries the model to the new provider's key when the provider changes", async () => {
    const user = userEvent.setup();
    const onChange = renderJudge({ type: "openai", parameters: { model: "llama3", apiKey: "k" } });
    await user.selectOptions(screen.getByTestId("cascade-judge-type"), "bedrock");
    expect(onChange.mock.lastCall![0]).toEqual({ type: "bedrock", parameters: { modelId: "llama3", apiKey: "k" } });
  });
});
