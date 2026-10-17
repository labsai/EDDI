import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TestSuiteEditor } from "@/components/editors/test-suite-editor";
import { EDITOR_MAP } from "@/components/editors/editor-registry";
import { MOCK_SUITE } from "@/test/mocks/agent-test-fixtures";
import type { AgentTestSuite } from "@/lib/api/agent-tests";

describe("TestSuiteEditor", () => {
  it("is registered for the testsuite resource type", () => {
    expect(EDITOR_MAP).toHaveProperty("testsuite");
  });

  it("adds a conversation with one empty turn", async () => {
    const onChange = vi.fn();
    renderWithProviders(<TestSuiteEditor data={{}} onChange={onChange} />);
    await userEvent.click(screen.getByTestId("add-case"));
    expect(onChange).toHaveBeenCalledWith({ cases: [{ name: "", turns: [{ input: "" }] }] });
  });

  it("renders the cases and mocks of an existing suite", () => {
    renderWithProviders(<TestSuiteEditor data={MOCK_SUITE as AgentTestSuite} onChange={vi.fn()} />);
    expect(screen.getAllByTestId("suite-case")).toHaveLength(2);
    expect(screen.getAllByTestId("suite-mock")).toHaveLength(2);
    expect(screen.getByTestId("suite-name")).toHaveValue("Booking agent — core flows");
  });

  it("writes an expectation into the turn", async () => {
    const onChange = vi.fn();
    const data: AgentTestSuite = { cases: [{ name: "c", turns: [{ input: "hi" }] }] };
    renderWithProviders(<TestSuiteEditor data={data} onChange={onChange} />);
    await userEvent.click(screen.getByTestId("turn-expect-toggle"));
    fireEvent.change(screen.getByTestId("expect-actions-contain"), { target: { value: "greet, ask_date" } });
    expect(onChange).toHaveBeenLastCalledWith({
      cases: [{ name: "c", turns: [{ input: "hi", expect: { actionsContain: ["greet", "ask_date"] } }] }],
    });
  });

  it("keeps the last valid JSON while a context is half-typed", () => {
    const onChange = vi.fn();
    const data: AgentTestSuite = { cases: [{ name: "c", turns: [{ input: "hi" }] }] };
    renderWithProviders(<TestSuiteEditor data={data} onChange={onChange} />);
    const ctx = screen.getAllByRole("textbox").find((el) => el.getAttribute("placeholder") === '{"language":"en"}')!;
    fireEvent.change(ctx, { target: { value: '{"language":' } });
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    fireEvent.change(ctx, { target: { value: '{"language":"en"}' } });
    expect(onChange).toHaveBeenLastCalledWith({
      cases: [{ name: "c", turns: [{ input: "hi" }], context: { language: "en" } }],
    });
  });

  it("is read-only: no add controls", () => {
    renderWithProviders(<TestSuiteEditor data={MOCK_SUITE as AgentTestSuite} onChange={vi.fn()} readOnly />);
    expect(screen.queryByTestId("add-case")).not.toBeInTheDocument();
    expect(screen.queryByTestId("add-mock")).not.toBeInTheDocument();
  });
});
