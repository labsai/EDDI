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
