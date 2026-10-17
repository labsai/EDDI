import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import type { ReactElement } from "react";
import { TaskGuardrailsSection } from "../task-guardrails-section";
import type { LlmTask } from "../types";

function Harness({
  initial,
  onChangeSpy,
  readOnly,
}: {
  initial: LlmTask;
  onChangeSpy?: (t: LlmTask) => void;
  readOnly?: boolean;
}) {
  const [task, setTask] = useState<LlmTask>(initial);
  return (
    <TaskGuardrailsSection
      task={task}
      readOnly={readOnly}
      onChange={(t) => {
        setTask(t);
        onChangeSpy?.(t);
      }}
    />
  );
}

/** The section starts collapsed when nothing is configured; open it like the author would. */
function show(ui: ReactElement) {
  const result = renderWithProviders(ui);
  if (!screen.queryByTestId("task-guardrails-section")) {
    fireEvent.click(screen.getByRole("button", { name: /Guardrails/ }));
  }
  return result;
}

const base = { type: "openai", parameters: {} } as LlmTask;

function last(spy: ReturnType<typeof vi.fn>): LlmTask {
  return spy.mock.calls[spy.mock.calls.length - 1]![0] as LlmTask;
}

describe("TaskGuardrailsSection", () => {
  it("writes nothing for a task the author has not touched", () => {
    const spy = vi.fn();
    show(<Harness initial={base} onChangeSpy={spy} />);
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByTestId("guardrails-input-empty")).toBeInTheDocument();
    expect(screen.getByTestId("guardrails-output-empty")).toBeInTheDocument();
  });

  it("offers only the types that run in each phase", () => {
    show(<Harness initial={base} />);
    const input = within(screen.getByTestId("guardrail-add-type-input")).getAllByRole("option");
    const output = within(screen.getByTestId("guardrail-add-type-output")).getAllByRole("option");
    expect(input.map((o) => (o as HTMLOptionElement).value)).toEqual([
      "prompt-injection",
      "pii-redaction",
      "regex-filter",
      "max-length",
      "classifier",
    ]);
    expect(output.map((o) => (o as HTMLOptionElement).value)).toEqual([
      "pii-redaction",
      "regex-filter",
      "max-length",
      "json-object",
      "classifier",
    ]);
  });

  it("adds a guardrail as just its type and opens the side sheet", () => {
    const spy = vi.fn();
    show(<Harness initial={base} onChangeSpy={spy} />);
    fireEvent.click(screen.getByTestId("guardrail-add-input"));
    expect(last(spy).inputGuardrails).toEqual([{ type: "prompt-injection" }]);
    // No action, no parameters: the engine's defaults apply and an older server sees nothing new.
    expect(last(spy)).not.toHaveProperty("outputGuardrails");
    expect(screen.getByTestId("guardrail-sheet")).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Input guardrail" })).toBeInTheDocument();
  });

  it("does not offer reprompt on the input side but does on the output side", () => {
    show(<Harness initial={base} />);
    fireEvent.click(screen.getByTestId("guardrail-add-input"));
    expect(within(screen.getByTestId("guardrail-action")).queryByText("Reprompt")).toBeNull();
    fireEvent.click(screen.getByTestId("guardrail-sheet-close"));
    fireEvent.click(screen.getByTestId("guardrail-add-output"));
    expect(within(screen.getByTestId("guardrail-action")).getByText("Reprompt")).toBeInTheDocument();
  });

  it("writes maxRetries only while the action is reprompt", () => {
    const spy = vi.fn();
    show(<Harness initial={base} onChangeSpy={spy} />);
    fireEvent.click(screen.getByTestId("guardrail-add-output"));
    fireEvent.change(screen.getByTestId("guardrail-action"), { target: { value: "reprompt" } });
    fireEvent.change(screen.getByTestId("guardrail-max-retries"), { target: { value: "3" } });
    expect(last(spy).outputGuardrails).toEqual([{ type: "pii-redaction", action: "reprompt", maxRetries: 3 }]);
    fireEvent.change(screen.getByTestId("guardrail-action"), { target: { value: "warn" } });
    expect(last(spy).outputGuardrails).toEqual([{ type: "pii-redaction", action: "warn" }]);
    expect(screen.queryByTestId("guardrail-max-retries")).toBeNull();
  });

  it("removes a blanked block message and an emptied parameter map", () => {
    const spy = vi.fn();
    show(
      <Harness
        initial={{ ...base, inputGuardrails: [{ type: "max-length", blockMessage: "no", parameters: { maxChars: "100" } }] }}
        onChangeSpy={spy}
      />,
    );
    fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
    fireEvent.change(screen.getByTestId("guardrail-block-message"), { target: { value: "" } });
    fireEvent.change(screen.getByTestId("guardrail-max-chars"), { target: { value: "" } });
    expect(last(spy).inputGuardrails).toEqual([{ type: "max-length" }]);
  });

  it("removing the last guardrail drops the field instead of writing []", () => {
    const spy = vi.fn();
    show(<Harness initial={{ ...base, inputGuardrails: [{ type: "prompt-injection" }] }} onChangeSpy={spy} />);
    fireEvent.click(screen.getByTestId("guardrail-remove-input-0"));
    expect(last(spy)).not.toHaveProperty("inputGuardrails");
  });

  it("reorders guardrails: order is the order they run in", () => {
    const spy = vi.fn();
    show(
      <Harness
        initial={{
          ...base,
          inputGuardrails: [{ type: "prompt-injection" }, { type: "pii-redaction" }, { type: "max-length" }],
        }}
        onChangeSpy={spy}
      />,
    );
    expect(screen.getByTestId("guardrail-up-input-0")).toBeDisabled();
    expect(screen.getByTestId("guardrail-down-input-2")).toBeDisabled();
    fireEvent.click(screen.getByTestId("guardrail-down-input-0"));
    expect(last(spy).inputGuardrails!.map((g) => g.type)).toEqual(["pii-redaction", "prompt-injection", "max-length"]);
    fireEvent.click(screen.getByTestId("guardrail-up-input-2"));
    expect(last(spy).inputGuardrails!.map((g) => g.type)).toEqual(["pii-redaction", "max-length", "prompt-injection"]);
  });

  describe("streaming caveat", () => {
    it("is a plain note while no output guardrail buffers", () => {
      show(
        <Harness initial={{ ...base, outputGuardrails: [{ type: "pii-redaction", action: "warn" }] }} />,
      );
      expect(screen.getByTestId("guardrails-streaming-note")).toHaveAttribute("data-buffered", "false");
    });

    it.each(["block", "redact", "reprompt"] as const)("flags a %s output guardrail as buffering", (action) => {
      show(<Harness initial={{ ...base, outputGuardrails: [{ type: "pii-redaction", action }] }} />);
      expect(screen.getByTestId("guardrails-streaming-note")).toHaveAttribute("data-buffered", "true");
    });

    it("treats an absent action as block, which is the engine's default", () => {
      show(<Harness initial={{ ...base, outputGuardrails: [{ type: "json-object" }] }} />);
      expect(screen.getByTestId("guardrails-streaming-note")).toHaveAttribute("data-buffered", "true");
    });

    it("ignores input guardrails: they never change streaming", () => {
      show(<Harness initial={{ ...base, inputGuardrails: [{ type: "prompt-injection" }] }} />);
      expect(screen.getByTestId("guardrails-streaming-note")).toHaveAttribute("data-buffered", "false");
    });
  });

  describe("type-specific forms", () => {
    it("pii-redaction: all kinds is stored as absent, a subset as a comma list", () => {
      const spy = vi.fn();
      show(<Harness initial={{ ...base, inputGuardrails: [{ type: "pii-redaction" }] }} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.getByTestId("guardrail-pii-email")).toBeChecked();
      fireEvent.click(screen.getByTestId("guardrail-pii-phone"));
      fireEvent.click(screen.getByTestId("guardrail-pii-ssn"));
      expect(last(spy).inputGuardrails![0]!.parameters).toEqual({ patterns: "email,creditcard" });
      fireEvent.click(screen.getByTestId("guardrail-pii-ssn"));
      fireEvent.click(screen.getByTestId("guardrail-pii-phone"));
      expect(last(spy).inputGuardrails).toEqual([{ type: "pii-redaction" }]);
    });

    it("pii-redaction: the last kind cannot be switched off", () => {
      show(
        <Harness initial={{ ...base, inputGuardrails: [{ type: "pii-redaction", parameters: { patterns: "email" } }] }} />,
      );
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.getByTestId("guardrail-pii-email")).toBeDisabled();
    });

    it("regex-filter: says a pattern is required until one is typed", () => {
      const spy = vi.fn();
      show(<Harness initial={{ ...base, outputGuardrails: [{ type: "regex-filter" }] }} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-output-0"));
      expect(screen.getByTestId("guardrail-patterns-required")).toBeInTheDocument();
      fireEvent.change(screen.getByTestId("guardrail-patterns"), { target: { value: "secret-\\d+" } });
      expect(screen.queryByTestId("guardrail-patterns-required")).toBeNull();
      expect(last(spy).outputGuardrails![0]!.parameters).toEqual({ patterns: "secret-\\d+" });
    });

    it("prompt-injection: replace mode is written, append (the default) is not", () => {
      const spy = vi.fn();
      show(<Harness initial={{ ...base, inputGuardrails: [{ type: "prompt-injection" }] }} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-mode"), { target: { value: "replace" } });
      expect(last(spy).inputGuardrails![0]!.parameters).toEqual({ mode: "replace" });
      fireEvent.change(screen.getByTestId("guardrail-mode"), { target: { value: "append" } });
      expect(last(spy).inputGuardrails).toEqual([{ type: "prompt-injection" }]);
    });

    it("json-object has no settings", () => {
      show(<Harness initial={{ ...base, outputGuardrails: [{ type: "json-object" }] }} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-output-0"));
      expect(screen.getByTestId("guardrail-json-note")).toBeInTheDocument();
    });

    it("warns that redact blocks for a type with nothing to rewrite with", () => {
      show(
        <Harness initial={{ ...base, inputGuardrails: [{ type: "prompt-injection", action: "redact" }] }} />,
      );
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.getByTestId("guardrail-no-rewrite")).toBeInTheDocument();
    });

    it("failMode: open on a blocking guardrail is called out", () => {
      const spy = vi.fn();
      show(<Harness initial={{ ...base, inputGuardrails: [{ type: "prompt-injection" }] }} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-fail-mode"), { target: { value: "open" } });
      expect(last(spy).inputGuardrails![0]!.parameters).toEqual({ failMode: "open" });
      expect(screen.getByTestId("guardrail-fail-open-warning")).toBeInTheDocument();
    });

    it("an unknown type is kept, labelled, and survives an edit of another field", () => {
      const spy = vi.fn();
      show(
        <Harness initial={{ ...base, inputGuardrails: [{ type: "future-thing", parameters: { x: "1" } }] }} onChangeSpy={spy} />,
      );
      expect(screen.getByText("future-thing (custom)")).toBeInTheDocument();
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-block-message"), { target: { value: "nope" } });
      expect(last(spy).inputGuardrails).toEqual([
        { type: "future-thing", parameters: { x: "1" }, blockMessage: "nope" },
      ]);
    });

    it("changing the type drops the old type's parameters but keeps failMode", () => {
      const spy = vi.fn();
      show(
        <Harness
          initial={{
            ...base,
            inputGuardrails: [{ type: "max-length", parameters: { maxChars: "10", failMode: "closed" } }],
          }}
          onChangeSpy={spy}
        />,
      );
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-type"), { target: { value: "regex-filter" } });
      expect(last(spy).inputGuardrails).toEqual([{ type: "regex-filter", parameters: { failMode: "closed" } }]);
    });
  });

  describe("classifier", () => {
    const withClassifier = (parameters: Record<string, string> = {}): LlmTask => ({
      ...base,
      inputGuardrails: [{ type: "classifier", parameters }],
    });

    it("requires a provider and says why", () => {
      show(<Harness initial={withClassifier()} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.getByTestId("guardrail-clf-provider-required")).toBeInTheDocument();
    });

    it("writes the model under the key the chosen provider reads", () => {
      const spy = vi.fn();
      show(<Harness initial={withClassifier({ provider: "ollama" })} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-clf-model"), { target: { value: "shieldgemma:2b" } });
      expect(last(spy).inputGuardrails![0]!.parameters).toEqual({ provider: "ollama", model: "shieldgemma:2b" });
    });

    it("moves the model to the new provider's key when the provider changes", () => {
      const spy = vi.fn();
      show(
        <Harness initial={withClassifier({ provider: "ollama", model: "shieldgemma:2b" })} onChangeSpy={spy} />,
      );
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-clf-provider"), { target: { value: "openai" } });
      expect(last(spy).inputGuardrails![0]!.parameters).toEqual({ provider: "openai", modelName: "shieldgemma:2b" });
    });

    it("requires a safe label once the unsafe label is not 'yes'", () => {
      show(<Harness initial={withClassifier({ provider: "ollama", unsafeLabel: "unsafe" })} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.getByTestId("guardrail-clf-safe-required")).toBeInTheDocument();
      fireEvent.change(screen.getByTestId("guardrail-clf-safe"), { target: { value: "safe" } });
      expect(screen.queryByTestId("guardrail-clf-safe-required")).toBeNull();
    });

    it("offers retrieved-context only on the output side", () => {
      show(
        <Harness
          initial={{
            ...base,
            inputGuardrails: [{ type: "classifier", parameters: { provider: "ollama" } }],
            outputGuardrails: [{ type: "classifier", parameters: { provider: "ollama" } }],
          }}
        />,
      );
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.queryByTestId("guardrail-clf-context")).toBeNull();
      fireEvent.click(screen.getByTestId("guardrail-sheet-close"));
      fireEvent.click(screen.getByTestId("guardrail-edit-output-0"));
      fireEvent.click(screen.getByTestId("guardrail-clf-context"));
    });

    it("writes the free-text and numeric parameters verbatim as strings", () => {
      const spy = vi.fn();
      show(<Harness initial={withClassifier({ provider: "openai" })} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      fireEvent.change(screen.getByTestId("guardrail-clf-base-url"), { target: { value: "http://vllm:8000/v1" } });
      fireEvent.change(screen.getByTestId("guardrail-clf-timeout"), { target: { value: "2000" } });
      fireEvent.change(screen.getByTestId("guardrail-clf-risk"), { target: { value: "jailbreak" } });
      fireEvent.change(screen.getByTestId("guardrail-clf-input-price"), { target: { value: "0.10" } });
      expect(last(spy).inputGuardrails![0]!.parameters).toEqual({
        provider: "openai",
        baseUrl: "http://vllm:8000/v1",
        timeoutMs: "2000",
        risk: "jailbreak",
        inputPricePer1M: "0.10",
      });
    });
  });

  describe("read-only", () => {
    it("hides add, disables remove and reorder, and still lets the sheet be opened", () => {
      show(
        <Harness readOnly initial={{ ...base, inputGuardrails: [{ type: "prompt-injection" }, { type: "max-length" }] }} />,
      );
      expect(screen.queryByTestId("guardrail-add-input")).toBeNull();
      expect(screen.getByTestId("guardrail-remove-input-0")).toBeDisabled();
      expect(screen.getByTestId("guardrail-down-input-0")).toBeDisabled();
      fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
      expect(screen.getByTestId("guardrail-action")).toBeDisabled();
    });
  });

  it("closes the sheet on Escape", () => {
    show(<Harness initial={{ ...base, inputGuardrails: [{ type: "prompt-injection" }] }} />);
    fireEvent.click(screen.getByTestId("guardrail-edit-input-0"));
    expect(screen.getByTestId("guardrail-sheet")).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByTestId("guardrail-sheet")).toBeNull();
  });
});
