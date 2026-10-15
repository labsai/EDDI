import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { TaskToolCallLimits } from "../task-tool-call-limits";
import { isUncappedToolCalls } from "../tool-call-limits";
import type { LlmTask } from "../types";

function Harness({ initial, onChangeSpy }: { initial: LlmTask; onChangeSpy?: (t: LlmTask) => void }) {
  const [task, setTask] = useState<LlmTask>(initial);
  return (
    <TaskToolCallLimits
      task={task}
      onChange={(t) => {
        setTask(t);
        onChangeSpy?.(t);
      }}
    />
  );
}

const base = { type: "openai", parameters: {} } as LlmTask;

describe("TaskToolCallLimits", () => {
  it("shows the server defaults as placeholders, not as values", () => {
    renderWithProviders(<Harness initial={base} />);
    const perResponse = screen.getByTestId("task-max-tool-calls-per-iteration");
    const perTurn = screen.getByTestId("task-max-tool-calls-per-turn");
    expect(perResponse).toHaveValue(null);
    expect(perResponse).toHaveAttribute("placeholder", "20");
    expect(perTurn).toHaveValue(null);
    expect(perTurn).toHaveAttribute("placeholder", "100");
  });

  it("labels both inputs, so a screen reader announces what they cap", () => {
    renderWithProviders(<Harness initial={base} />);
    expect(screen.getByLabelText("Max tool calls per response")).toBe(
      screen.getByTestId("task-max-tool-calls-per-iteration"),
    );
    expect(screen.getByLabelText("Max tool calls per turn")).toBe(screen.getByTestId("task-max-tool-calls-per-turn"));
  });

  it("explains what happens past a cap", () => {
    renderWithProviders(<Harness initial={base} />);
    expect(screen.getByText(/Calls past a cap are not run/)).toBeInTheDocument();
  });

  it("writes typed caps onto the task", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={base} onChangeSpy={spy} />);
    fireEvent.change(screen.getByTestId("task-max-tool-calls-per-iteration"), { target: { value: "5" } });
    expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ maxToolCallsPerIteration: 5 }));
    fireEvent.change(screen.getByTestId("task-max-tool-calls-per-turn"), { target: { value: "30" } });
    expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ maxToolCallsPerIteration: 5, maxToolCallsPerTurn: 30 }));
  });

  it("clearing a cap removes the field (an older backend refuses unknown fields)", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={{ ...base, maxToolCallsPerTurn: 40 }} onChangeSpy={spy} />);
    fireEvent.change(screen.getByTestId("task-max-tool-calls-per-turn"), { target: { value: "" } });
    expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ maxToolCallsPerTurn: undefined }));
  });

  it("warns when a cap is switched off with -1 or 0, and not otherwise", () => {
    const { unmount } = renderWithProviders(<Harness initial={{ ...base, maxToolCallsPerIteration: 20 }} />);
    expect(screen.queryByTestId("task-tool-calls-uncapped")).not.toBeInTheDocument();
    unmount();
    renderWithProviders(<Harness initial={{ ...base, maxToolCallsPerTurn: -1 }} />);
    expect(screen.getByTestId("task-tool-calls-uncapped")).toBeInTheDocument();
  });

  it("reads -1 and 0 as uncapped and absence as the default", () => {
    expect(isUncappedToolCalls(-1)).toBe(true);
    expect(isUncappedToolCalls(0)).toBe(true);
    expect(isUncappedToolCalls(1)).toBe(false);
    expect(isUncappedToolCalls(undefined)).toBe(false);
  });

  it("is read-only when the editor is", () => {
    renderWithProviders(<TaskToolCallLimits task={base} onChange={() => {}} readOnly />);
    expect(screen.getByTestId("task-max-tool-calls-per-turn")).toHaveAttribute("readonly");
  });

  describe("repeated failing tool calls", () => {
    it("shows the defaults as placeholders and the guard as on, without writing anything", () => {
      renderWithProviders(<Harness initial={base} />);
      expect(screen.getByTestId("task-repeat-guard-enabled")).toBeChecked();
      expect(screen.getByTestId("task-repeat-guard-max-failures")).toHaveAttribute("placeholder", "2");
      expect(screen.getByTestId("task-repeat-guard-max-refusals")).toHaveAttribute("placeholder", "3");
      expect(screen.getByTestId("task-repeat-guard-max-identical")).toHaveAttribute("placeholder", "-1");
    });

    it("writes only what the author changed", () => {
      const spy = vi.fn();
      renderWithProviders(<Harness initial={base} onChangeSpy={spy} />);
      fireEvent.change(screen.getByTestId("task-repeat-guard-max-failures"), {
        target: { value: "4" },
      });
      expect(spy).toHaveBeenLastCalledWith(
        expect.objectContaining({
          toolRepeatGuard: { maxIdenticalFailures: 4 },
        }),
      );
      fireEvent.change(screen.getByTestId("task-repeat-guard-max-refusals"), {
        target: { value: "5" },
      });
      expect(spy).toHaveBeenLastCalledWith(
        expect.objectContaining({
          toolRepeatGuard: { maxIdenticalFailures: 4, maxRefusalsPerTurn: 5 },
        }),
      );
    });

    it("unticking writes enabled:false; ticking again removes the whole field", () => {
      const spy = vi.fn();
      renderWithProviders(<Harness initial={base} onChangeSpy={spy} />);
      fireEvent.click(screen.getByTestId("task-repeat-guard-enabled"));
      expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ toolRepeatGuard: { enabled: false } }));
      fireEvent.click(screen.getByTestId("task-repeat-guard-enabled"));
      expect(spy.mock.lastCall?.[0].toolRepeatGuard).toBeUndefined();
    });

    it("parses exempt tools from a comma-separated list and drops the list when cleared", () => {
      const spy = vi.fn();
      renderWithProviders(<Harness initial={base} onChangeSpy={spy} />);
      fireEvent.change(screen.getByTestId("task-repeat-guard-exempt"), {
        target: { value: "get_job, poll_status ," },
      });
      expect(spy).toHaveBeenLastCalledWith(
        expect.objectContaining({
          toolRepeatGuard: { exemptTools: ["get_job", "poll_status"] },
        }),
      );
      fireEvent.change(screen.getByTestId("task-repeat-guard-exempt"), {
        target: { value: "" },
      });
      expect(spy.mock.lastCall?.[0].toolRepeatGuard).toBeUndefined();
    });

    it("shows a stored configuration and is read-only when the editor is", () => {
      renderWithProviders(
        <TaskToolCallLimits
          task={{
            ...base,
            toolRepeatGuard: {
              enabled: false,
              maxIdenticalFailures: 7,
              exemptTools: ["a", "b"],
            },
          }}
          onChange={() => {}}
          readOnly
        />,
      );
      expect(screen.getByTestId("task-repeat-guard-enabled")).not.toBeChecked();
      expect(screen.getByTestId("task-repeat-guard-enabled")).toBeDisabled();
      expect(screen.getByTestId("task-repeat-guard-max-failures")).toHaveValue(7);
      expect(screen.getByTestId("task-repeat-guard-exempt")).toHaveValue("a, b");
    });
  });
});
