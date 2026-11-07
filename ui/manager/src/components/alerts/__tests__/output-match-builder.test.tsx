import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { OutputMatchBuilder } from "../output-match-builder";
import type { AlertCondition } from "@/lib/api/alerts";

function renderBuilder(condition: AlertCondition) {
  const onChange = vi.fn();
  renderWithProviders(<OutputMatchBuilder condition={condition} onChange={onChange} />);
  return onChange;
}

describe("OutputMatchBuilder — switching the path source", () => {
  it("to actions turns a number into text, since contains compares strings", () => {
    const onChange = renderBuilder({ path: "properties.score", op: "eq", value: 3 });
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "actions" } });
    expect(onChange).toHaveBeenCalledWith({ path: "actions", op: "contains", value: "3" });
  });

  it("to actions turns a boolean into text", () => {
    const onChange = renderBuilder({ path: "properties.escalate", op: "eq", value: true });
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "actions" } });
    expect(onChange).toHaveBeenCalledWith({ path: "actions", op: "contains", value: "true" });
  });

  it("to actions turns a list into text", () => {
    const onChange = renderBuilder({ path: "output.json.sentiment", op: "in", value: ["angry", "frustrated"] });
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "actions" } });
    expect(onChange).toHaveBeenCalledWith({ path: "actions", op: "contains", value: "angry, frustrated" });
  });

  it("to actions keeps exists without a value", () => {
    const onChange = renderBuilder({ path: "properties.escalate", op: "exists", value: null });
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "actions" } });
    expect(onChange).toHaveBeenCalledWith({ path: "actions", op: "exists", value: null });
  });

  it("to another source leaves the value alone", () => {
    const onChange = renderBuilder({ path: "properties.score", op: "gt", value: 3 });
    fireEvent.change(screen.getByTestId("output-match-source"), { target: { value: "output.json" } });
    expect(onChange).toHaveBeenCalledWith({ path: "output.json.score", op: "gt" });
  });
});

/** The builder as the rule editor holds it: every change patches the condition it is rendered with. */
function StatefulBuilder({ initial }: { initial: AlertCondition }) {
  const [condition, setCondition] = useState(initial);
  return <OutputMatchBuilder condition={condition} onChange={(patch) => setCondition((c) => ({ ...c, ...patch }))} />;
}

const NUMERIC_OPS = ["eq", "neq", "gt", "gte", "lt", "lte", "exists"];

function opValues() {
  return Array.from((screen.getByTestId("output-match-op") as HTMLSelectElement).options).map((o) => o.value);
}

describe("OutputMatchBuilder — the value type is the author's choice, not read back from the value", () => {
  it("Number can be chosen while the value is still empty, and offers the numeric comparisons", () => {
    renderWithProviders(<StatefulBuilder initial={{ path: "properties.score", op: "eq", value: "" }} />);
    fireEvent.change(screen.getByTestId("output-match-type"), { target: { value: "number" } });
    expect(screen.getByTestId("output-match-type")).toHaveValue("number");
    expect(opValues()).toEqual(NUMERIC_OPS);
    expect(screen.getByTestId("output-match-value")).toHaveAttribute("type", "number");
  });

  it("stays Number while a negative number is typed and when the field is cleared", () => {
    renderWithProviders(<StatefulBuilder initial={{ path: "properties.score", op: "eq", value: "" }} />);
    fireEvent.change(screen.getByTestId("output-match-type"), { target: { value: "number" } });
    fireEvent.change(screen.getByTestId("output-match-op"), { target: { value: "lt" } });
    const value = () => screen.getByTestId("output-match-value");
    // A number input reports a lone "-" as "".
    fireEvent.change(value(), { target: { value: "-" } });
    expect(screen.getByTestId("output-match-type")).toHaveValue("number");
    fireEvent.change(value(), { target: { value: "-5" } });
    expect(value()).toHaveValue(-5);
    expect(screen.getByTestId("output-match-op")).toHaveValue("lt");
    fireEvent.change(value(), { target: { value: "" } });
    expect(screen.getByTestId("output-match-type")).toHaveValue("number");
    expect(opValues()).toEqual(NUMERIC_OPS);
  });

  it("follows a condition replaced from outside, such as another rule or version being loaded", () => {
    const { rerender } = renderWithProviders(
      <OutputMatchBuilder condition={{ path: "properties.score", op: "eq", value: "high" }} onChange={vi.fn()} />,
    );
    expect(screen.getByTestId("output-match-type")).toHaveValue("text");
    rerender(<OutputMatchBuilder condition={{ path: "properties.score", op: "gt", value: 3 }} onChange={vi.fn()} />);
    expect(screen.getByTestId("output-match-type")).toHaveValue("number");
    rerender(<OutputMatchBuilder condition={{ path: "properties.flag", op: "eq", value: true }} onChange={vi.fn()} />);
    expect(screen.getByTestId("output-match-type")).toHaveValue("boolean");
  });
});
