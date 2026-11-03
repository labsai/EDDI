import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import type { ReactElement } from "react";
import { TaskToolCallGuardrailsSection } from "../task-tool-call-guardrails-section";
import { pointerProblem, textToFields, toolPatternProblem } from "../tool-call-guardrails";
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
    <TaskToolCallGuardrailsSection
      task={task}
      readOnly={readOnly}
      onChange={(t) => {
        setTask(t);
        onChangeSpy?.(t);
      }}
    />
  );
}

function show(ui: ReactElement) {
  const result = renderWithProviders(ui);
  if (!screen.queryByTestId("task-tool-call-guardrails-section")) {
    fireEvent.click(screen.getByRole("button", { name: /Tool-call guardrails/ }));
  }
  return result;
}

const base = { type: "openai", parameters: {} } as LlmTask;

function last(spy: ReturnType<typeof vi.fn>): LlmTask {
  return spy.mock.calls[spy.mock.calls.length - 1]![0] as LlmTask;
}

describe("TaskToolCallGuardrailsSection", () => {
  it("writes nothing for a task the author has not touched", () => {
    const spy = vi.fn();
    show(<Harness initial={base} onChangeSpy={spy} />);
    expect(spy).not.toHaveBeenCalled();
    expect(screen.getByTestId("tool-call-guardrails-empty")).toBeInTheDocument();
  });

  it("offers every type that runs on tool-call arguments, judge and disclosure included", () => {
    show(<Harness initial={base} />);
    const options = within(screen.getByTestId("tool-call-guardrail-add-type"))
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual([
      "prompt-injection",
      "pii-redaction",
      "regex-filter",
      "max-length",
      "classifier",
      "judge",
      "json-object",
      "expression",
      "disclosure",
    ]);
  });

  it("adds an entry with empty tools, flags them as required, and writes the tools and fields typed", () => {
    const spy = vi.fn();
    show(<Harness initial={base} onChangeSpy={spy} />);
    fireEvent.change(screen.getByTestId("tool-call-guardrail-add-type"), { target: { value: "regex-filter" } });
    fireEvent.click(screen.getByTestId("tool-call-guardrail-add"));
    expect(last(spy).toolCallGuardrails).toEqual([{ type: "regex-filter", tools: [] }]);
    expect(screen.getByRole("dialog", { name: "Tool-call guardrail" })).toBeInTheDocument();
    expect(screen.getByTestId("tool-call-guardrail-tools-required")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("tool-call-guardrail-tools"), {
      target: { value: "mcp:add_issue_comment\n\nmcp:create_issue\n" },
    });
    fireEvent.change(screen.getByTestId("tool-call-guardrail-fields"), { target: { value: '/body\n""' } });
    expect(last(spy).toolCallGuardrails).toEqual([
      { type: "regex-filter", tools: ["mcp:add_issue_comment", "mcp:create_issue"], fields: ["/body", ""] },
    ]);
    expect(screen.queryByTestId("tool-call-guardrail-tools-required")).toBeNull();
  });

  it("keeps tools and fields when the guardrail part is edited in the sheet", () => {
    const spy = vi.fn();
    show(
      <Harness
        initial={{ ...base, toolCallGuardrails: [{ type: "regex-filter", tools: ["mcp:x"], fields: ["/body"] }] }}
        onChangeSpy={spy}
      />,
    );
    fireEvent.click(screen.getByTestId("tool-call-guardrail-edit-0"));
    fireEvent.change(screen.getByTestId("guardrail-action"), { target: { value: "reprompt" } });
    fireEvent.change(screen.getByTestId("guardrail-max-retries"), { target: { value: "1" } });
    expect(last(spy).toolCallGuardrails).toEqual([
      { type: "regex-filter", tools: ["mcp:x"], fields: ["/body"], action: "reprompt", maxRetries: 1 },
    ]);
    // Deliver-best has no meaning for a call: the sheet does not offer it here.
    expect(screen.queryByTestId("guardrail-on-exhausted")).toBeNull();
  });

  it("starts a disclosure entry on redact and edits its label", () => {
    const spy = vi.fn();
    show(<Harness initial={base} onChangeSpy={spy} />);
    fireEvent.change(screen.getByTestId("tool-call-guardrail-add-type"), { target: { value: "disclosure" } });
    fireEvent.click(screen.getByTestId("tool-call-guardrail-add"));
    expect(screen.getByTestId("guardrail-disclosure-text-required")).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("guardrail-disclosure-text"), { target: { value: "— AI-assisted reply" } });
    expect(last(spy).toolCallGuardrails).toEqual([
      { type: "disclosure", action: "redact", tools: [], parameters: { text: "— AI-assisted reply" } },
    ]);
    fireEvent.change(screen.getByTestId("guardrail-action"), { target: { value: "block" } });
    expect(screen.getByTestId("guardrail-disclosure-action")).toBeInTheDocument();
  });

  it("flags invalid tool patterns and field pointers, and a model-backed check without fields", () => {
    show(<Harness initial={{ ...base, toolCallGuardrails: [{ type: "judge", tools: ["mcp:x"] }] }} />);
    fireEvent.click(screen.getByTestId("tool-call-guardrail-edit-0"));
    expect(screen.getByTestId("tool-call-guardrail-fields-model")).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("tool-call-guardrail-tools"), { target: { value: "bad pattern!" } });
    expect(screen.getByTestId("tool-call-guardrail-tools-invalid")).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("tool-call-guardrail-fields"), { target: { value: "/items/x*" } });
    expect(screen.getByTestId("tool-call-guardrail-fields-invalid")).toBeInTheDocument();
  });

  it("removing the last entry drops the field instead of writing []", () => {
    const spy = vi.fn();
    show(<Harness initial={{ ...base, toolCallGuardrails: [{ type: "regex-filter", tools: ["mcp:x"] }] }} onChangeSpy={spy} />);
    fireEvent.click(screen.getByTestId("tool-call-guardrail-remove-0"));
    expect(last(spy)).not.toHaveProperty("toolCallGuardrails");
  });

  it("shows tools and fields on the row; every string when no fields", () => {
    show(<Harness initial={{ ...base, toolCallGuardrails: [{ type: "pii-redaction", tools: ["mcp:a", "mcp:b"] }] }} />);
    expect(screen.getByTestId("tool-call-guardrail-target-0").textContent).toContain("mcp:a, mcp:b · every string");
  });

  it("read-only: no add, no remove", () => {
    show(<Harness initial={{ ...base, toolCallGuardrails: [{ type: "regex-filter", tools: ["mcp:x"] }] }} readOnly />);
    expect(screen.queryByTestId("tool-call-guardrail-add")).toBeNull();
    expect(screen.getByTestId("tool-call-guardrail-remove-0")).toBeDisabled();
  });
});

describe("tool-call guardrail helpers", () => {
  it("validates pointers like the server", () => {
    expect(pointerProblem("")).toBe(false);
    expect(pointerProblem("/body")).toBe(false);
    expect(pointerProblem("/items/*/text")).toBe(false);
    expect(pointerProblem("/a~1b~0c")).toBe(false);
    expect(pointerProblem("body")).toBe(true);
    expect(pointerProblem("/a~2")).toBe(true);
    expect(pointerProblem("/items/x*")).toBe(true);
  });

  it("validates tool pattern characters", () => {
    expect(toolPatternProblem("http.post:/issues/{id}")).toBe(false);
    expect(toolPatternProblem("mcp:*")).toBe(false);
    expect(toolPatternProblem("bad pattern")).toBe(true);
    expect(toolPatternProblem("mcp:")).toBe(true);
  });

  it('reads "" as the whole arguments', () => {
    expect(textToFields('/a\n""\n  \n/b')).toEqual(["/a", "", "/b"]);
  });
});
