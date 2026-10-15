import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ModelSettingsSection } from "@/components/editors/llm/model-settings-section";

const P = "ms";

/** Holds the parameters like the real editor does, and reports every change. */
function Harness({
  type,
  initial,
  onChange,
  readOnly,
  inherited,
}: {
  type: string;
  initial: Record<string, string>;
  onChange?: (next: Record<string, string>) => void;
  readOnly?: boolean;
  inherited?: Record<string, string>;
}) {
  const [params, setParams] = useState(initial);
  return (
    <>
      <input
        aria-label="model"
        data-testid="harness-model"
        value={params.modelName ?? ""}
        onChange={(e) => setParams({ ...params, modelName: e.target.value })}
      />
      <ModelSettingsSection
        type={type}
        parameters={params}
        onChange={(next) => {
          setParams(next);
          onChange?.(next);
        }}
        readOnly={readOnly}
        inheritedParameters={inherited}
        testIdPrefix={P}
      />
    </>
  );
}

const row = (key: string) => screen.getByTestId(`${P}-param-${key}`);
const input = (key: string) => screen.getByTestId(`${P}-param-${key}-input`);

describe("ModelSettingsSection", () => {
  it("renders the openai catalog grouped, common groups open and the rest collapsed", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5.4" }} />);
    expect(screen.getByTestId(P)).toHaveAttribute("data-provider", "openai");
    expect(screen.getByTestId(`${P}-group-sampling`)).toHaveAttribute("data-open", "true");
    expect(screen.getByTestId(`${P}-group-reasoning`)).toHaveAttribute("data-open", "true");
    expect(screen.getByTestId(`${P}-group-advanced`)).toHaveAttribute("data-open", "false");
    expect(row("topP")).toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-param-customHeaders`)).not.toBeInTheDocument();
    // Typed controls: a select for an enum, a radio group for a boolean.
    expect(input("reasoningEffort").tagName).toBe("SELECT");
    expect(within(input("returnThinking")).getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByTestId(`${P}-configured-count`)).toHaveAttribute("data-count", "0");
  });

  it("opens a collapsed group that holds a configured value and badges its count", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o", store: "true", metadata: "{}" }} />);
    expect(screen.getByTestId(`${P}-group-advanced`)).toHaveAttribute("data-open", "true");
    expect(screen.getByTestId(`${P}-group-advanced-count`)).toHaveTextContent("2");
    expect(screen.getByTestId(`${P}-configured-count`)).toHaveAttribute("data-count", "2");
  });

  it("toggles a group from its header", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o" }} />);
    const toggle = screen.getByTestId(`${P}-group-advanced-toggle`);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(row("customHeaders")).toBeInTheDocument();
  });

  it("renders anthropic's thinking settings", () => {
    renderWithProviders(<Harness type="anthropic" initial={{ modelName: "claude-opus-4-6" }} />);
    expect(screen.getByTestId(P)).toHaveAttribute("data-provider", "anthropic");
    expect(input("thinkingType").tagName).toBe("SELECT");
    expect(row("thinkingBudgetTokens")).toBeInTheDocument();
  });

  it("renders gemini and locks a setting the model rejects for every value", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="gemini" initial={{ modelName: "gemini-2.5-flash" }} />);
    // Unset and rejected: behind the group's one quiet line until asked for.
    expect(screen.queryByTestId(`${P}-param-thinkingLevel`)).not.toBeInTheDocument();
    await user.click(screen.getByTestId(`${P}-group-reasoning-unavailable-toggle`));
    expect(row("thinkingLevel")).toHaveAttribute("data-state", "rejected");
    expect(input("thinkingLevel")).toBeDisabled();
    expect(row("thinkingBudget")).toHaveAttribute("data-state", "ok");
  });

  it("renders an OpenAI-compatible provider with inherited and own keys, evaluated against its default model", () => {
    renderWithProviders(<Harness type="deepseek" initial={{ temperature: "0.5" }} />);
    expect(screen.getByTestId(P)).toHaveAttribute("data-provider", "deepseek");
    expect(row("thinkingType")).toBeInTheDocument(); // deepseek's own
    expect(row("seed")).toBeInTheDocument(); // inherited from openai
    // No model set: the backend falls back to deepseek-flash, which ignores temperature.
    expect(row("temperature")).toHaveAttribute("data-state", "ignored");
    expect(screen.getByTestId(`${P}-param-temperature-verdict`)).toHaveTextContent("deepseek-flash");
    expect(screen.getByTestId(`${P}-ignored`)).toHaveAttribute("data-count", "1");
  });

  it("flips a parameter to rejected when the model changes, and Remove drops it", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o", topP: "0.9" }} onChange={onChange} />);
    expect(row("topP")).toHaveAttribute("data-state", "ok");
    expect(screen.queryByTestId(`${P}-dropped`)).not.toBeInTheDocument();

    const model = screen.getByTestId("harness-model");
    await user.clear(model);
    await user.type(model, "gpt-5");
    expect(row("topP")).toHaveAttribute("data-state", "rejected");
    expect(input("topP")).toBeDisabled();
    expect(screen.getByTestId(`${P}-dropped`)).toHaveTextContent("1 will be dropped for gpt-5");
    expect(screen.getByTestId(`${P}-group-sampling-rejected`)).toBeInTheDocument();

    await user.click(screen.getByTestId(`${P}-param-topP-verdict-remove`));
    expect(onChange.mock.lastCall![0]).toEqual({ modelName: "gpt-5" });
    // Now unset and rejected, so it joins the group's collapsed line.
    expect(screen.queryByTestId(`${P}-param-topP`)).not.toBeInTheDocument();
    expect(screen.getByTestId(`${P}-group-sampling-unavailable-toggle`)).toHaveTextContent("settings that gpt-5 rejects or ignores");
    expect(screen.queryByTestId(`${P}-dropped`)).not.toBeInTheDocument();
  });

  it("re-evaluates when a related parameter changes", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5.5", temperature: "0.4" }} />);
    // gpt-5.5 accepts sampling settings only with reasoningEffort none.
    // No temperature value can fix that, so the control is locked until reasoningEffort changes.
    expect(row("temperature")).toHaveAttribute("data-state", "rejected");
    expect(input("temperature")).toBeDisabled();
    await user.selectOptions(input("reasoningEffort"), "none");
    expect(row("temperature")).toHaveAttribute("data-state", "ok");
    expect(input("temperature")).not.toBeDisabled();
  });

  it("marks enum options the model rejects and disables them", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5" }} />);
    const none = within(input("reasoningEffort")).getByRole("option", { name: /^none/ });
    expect(none).toBeDisabled();
    expect(none).toHaveTextContent("rejected by this model");
    expect(within(input("reasoningEffort")).getByRole("option", { name: "low" })).not.toBeDisabled();
  });

  it("jumps to the offending parameter from the summary", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5", stopSequences: "END", reasoningEffort: "none" }} />);
    await user.click(screen.getByTestId(`${P}-dropped`));
    // reasoningEffort none on gpt-5 is rejected for that value only, so its select takes focus.
    expect(input("reasoningEffort")).toHaveFocus();
  });

  it("validates JSON and formats it once valid", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o", customHeaders: "{" }} onChange={onChange} />);
    expect(screen.getByTestId(`${P}-param-customHeaders-input-error`)).toBeInTheDocument();
    expect(input("customHeaders")).toHaveAttribute("aria-invalid", "true");

    await user.clear(input("customHeaders"));
    await user.type(input("customHeaders"), '{{"X-A":"1"}');
    expect(screen.queryByTestId(`${P}-param-customHeaders-input-error`)).not.toBeInTheDocument();
    await user.click(screen.getByTestId(`${P}-param-customHeaders-input-format`));
    expect(onChange.mock.lastCall![0].customHeaders).toBe('{\n  "X-A": "1"\n}');

    await user.clear(input("customHeaders"));
    await user.type(input("customHeaders"), "42");
    expect(screen.getByTestId(`${P}-param-customHeaders-input-error`)).toHaveTextContent("JSON object");
  });

  it("edits a stringList as chips, reading a comma list and writing a JSON array", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o", stopSequences: "END, STOP" }} onChange={onChange} />);
    expect(screen.getByTestId(`${P}-param-stopSequences-input-item-END`)).toBeInTheDocument();
    expect(screen.getByTestId(`${P}-param-stopSequences-input-item-STOP`)).toBeInTheDocument();

    // One value per chip; a comma inside a value survives because the list is written as JSON.
    await user.type(screen.getByTestId(`${P}-param-stopSequences-input-input`), "a, b{Enter}");
    expect(onChange.mock.lastCall![0].stopSequences).toBe('["END","STOP","a, b"]');

    await user.click(screen.getByTestId(`${P}-param-stopSequences-input-remove-END`));
    expect(onChange.mock.lastCall![0].stopSequences).toBe('["STOP","a, b"]');
  });

  it("sets a boolean on, and back to the provider default by removing the key", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o" }} onChange={onChange} />);
    await user.click(screen.getByTestId(`${P}-group-tools-toggle`));
    await user.click(screen.getByTestId(`${P}-param-parallelToolCalls-input-true`));
    expect(onChange.mock.lastCall![0]).toMatchObject({ parallelToolCalls: "true" });
    await user.click(screen.getByTestId(`${P}-param-parallelToolCalls-input-default`));
    expect(onChange.mock.lastCall![0]).not.toHaveProperty("parallelToolCalls");
  });

  it("resets a configured value to the provider default", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o", seed: "7" }} onChange={onChange} />);
    expect(input("seed")).toHaveValue(7);
    await user.click(screen.getByTestId(`${P}-param-seed-reset`));
    expect(onChange.mock.lastCall![0]).toEqual({ modelName: "gpt-4o" });
    expect(screen.queryByTestId(`${P}-param-seed-reset`)).not.toBeInTheDocument();
  });

  it("links every parameter to its official source", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o" }} />);
    const docs = screen.getByTestId(`${P}-param-topP-docs`);
    expect(docs).toHaveAttribute("href", expect.stringMatching(/^https:\/\//));
    expect(docs).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("filters by label, key and description, opening matching groups", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o" }} />);
    await user.type(screen.getByTestId(`${P}-filter`), "header");
    expect(row("customHeaders")).toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-param-topP`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-group-sampling`)).not.toBeInTheDocument();

    await user.clear(screen.getByTestId(`${P}-filter`));
    await user.type(screen.getByTestId(`${P}-filter`), "no-such-setting");
    expect(screen.getByTestId(`${P}-no-match`)).toBeInTheDocument();
  });

  it("is read-only everywhere", () => {
    renderWithProviders(
      <Harness
        type="openai"
        initial={{ modelName: "gpt-5", topP: "0.9", seed: "1", stopSequences: "END", parallelToolCalls: "true", reasoningEffort: "low", customHeaders: "{}" }}
        readOnly
      />,
    );
    expect(input("seed")).toHaveAttribute("readonly");
    expect(input("reasoningEffort")).toBeDisabled();
    expect(input("customHeaders")).toHaveAttribute("readonly");
    for (const radio of within(input("parallelToolCalls")).getAllByRole("radio")) expect(radio).toBeDisabled();
    expect(screen.queryByTestId(`${P}-param-stopSequences-input-input`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-param-seed-reset`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-param-topP-verdict-remove`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-param-customHeaders-input-format`)).not.toBeInTheDocument();
  });

  it("evaluates a cascade step with the inherited task parameters underneath and shows them as placeholders", () => {
    renderWithProviders(
      <Harness type="openai" initial={{ model: "gpt-5" }} inherited={{ topP: "0.8", seed: "3" }} />,
    );
    // topP comes from the task but the step's model rejects it.
    expect(row("topP")).toHaveAttribute("data-state", "rejected");
    expect(input("seed")).toHaveAttribute("placeholder", "3");
  });

  it("renders nothing for a provider the catalog does not describe", () => {
    renderWithProviders(<Harness type="acme" initial={{ modelName: "x" }} />);
    expect(screen.queryByTestId(P)).not.toBeInTheDocument();
  });

  it("collapses unset settings the model rejects or ignores behind one line per group", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="openai" initial={{ modelName: "o3", seed: "1" }} />);
    const toggle = screen.getByTestId(`${P}-group-sampling-unavailable-toggle`);
    // temperature, topP, frequencyPenalty, presencePenalty and logitBias; seed is set and stays visible.
    expect(toggle).toHaveAttribute("data-count", "5");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(row("seed")).toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-param-topP`)).not.toBeInTheDocument();
    await user.click(toggle);
    expect(row("topP")).toHaveAttribute("data-state", "rejected");
  });

  it("labels every group count for screen readers and on hover", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5", topP: "0.5", seed: "1" }} />);
    expect(screen.getByTestId(`${P}-group-sampling-total`)).toHaveAttribute("title", "Settings in this group: 6");
    expect(screen.getByTestId(`${P}-group-sampling-count`)).toHaveTextContent("Configured: 2");
    expect(screen.getByTestId(`${P}-group-sampling-rejected`)).toHaveAttribute("title", "Rejected by the model: 1");
  });

  it("says a rejected maxTokens is sent as maxCompletionTokens, with no drop warning or Remove", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5", maxTokens: "500" }} />);
    const note = screen.getByTestId(`${P}-param-maxTokens-verdict`);
    expect(note).toHaveAttribute("data-migrates-to", "maxCompletionTokens");
    expect(note).toHaveTextContent("Sent as maxCompletionTokens for gpt-5");
    expect(screen.queryByTestId(`${P}-param-maxTokens-verdict-remove`)).not.toBeInTheDocument();
    expect(input("maxTokens")).not.toBeDisabled();
    expect(screen.queryByTestId(`${P}-dropped`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`${P}-group-output-rejected`)).not.toBeInTheDocument();
  });

  it("drops maxTokens like any rejection once maxCompletionTokens is set too", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5", maxTokens: "500", maxCompletionTokens: "800" }} />);
    expect(screen.getByTestId(`${P}-param-maxTokens-verdict`)).not.toHaveAttribute("data-migrates-to");
    expect(screen.getByTestId(`${P}-param-maxTokens-verdict-remove`)).toBeInTheDocument();
    expect(screen.getByTestId(`${P}-dropped`)).toHaveAttribute("data-count", "1");
  });

  it("notes that model checks wait for a variable model reference to resolve", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "${vars.default-model}" }} />);
    expect(screen.getByTestId(`${P}-variable-model`)).toBeInTheDocument();
  });

  it("does not show the variable note for a plain model id", () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-5" }} />);
    expect(screen.queryByTestId(`${P}-variable-model`)).not.toBeInTheDocument();
  });

  it("reads numbers and booleans typed in the JSON tab, and saves untouched keys unchanged", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    // The JSON tab can store real JSON numbers and booleans in the parameter map.
    const initial = { modelName: "gpt-5", topP: 0.9, seed: 3, store: true, metadata: { a: 1 } } as unknown as Record<string, string>;
    renderWithProviders(<Harness type="openai" initial={initial} onChange={onChange} />);
    expect(row("topP")).toHaveAttribute("data-state", "rejected");
    expect(row("topP")).toHaveAttribute("data-configured");
    expect(input("seed")).toHaveValue(3);
    expect(screen.getByTestId(`${P}-param-store-input-true`)).toBeChecked();
    expect(screen.getByTestId(`${P}-dropped`)).toHaveAttribute("data-count", "1");

    await user.click(screen.getByTestId(`${P}-param-seed-reset`));
    expect(onChange.mock.lastCall![0]).toEqual({ modelName: "gpt-5", topP: 0.9, store: true, metadata: { a: 1 } });
  });

  it("requires each JSON parameter's declared shape", async () => {
    renderWithProviders(<Harness type="openai" initial={{ modelName: "gpt-4o", customHeaders: "[]" }} />);
    // Headers are a map: an array is refused even though it is valid JSON.
    expect(screen.getByTestId(`${P}-param-customHeaders-input-error`)).toHaveTextContent("JSON object");
    expect(input("customHeaders")).toHaveAttribute("aria-invalid", "true");
  });

  it("requires an array for OpenRouter plugins and refuses an object", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness type="openrouter" initial={{ modelName: "openrouter/auto", plugins: "{}" }} />);
    expect(screen.getByTestId(`${P}-param-plugins-input-error`)).toHaveTextContent("JSON array");
    expect(input("plugins")).toHaveAttribute("placeholder", "[]");
    await user.clear(input("plugins"));
    await user.type(input("plugins"), '[[{{"id":"web"}]');
    expect(screen.queryByTestId(`${P}-param-plugins-input-error`)).not.toBeInTheDocument();
  });
});
