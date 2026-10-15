import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";
import { DebouncedInput, DebouncedNumberInput } from "../debounced-inputs";

/**
 * Exercises the real components. The file this replaces re-implemented the
 * parse rule in the test (`parseFloat(raw) || fallback`) and asserted that a
 * typed 0 became the fallback — i.e. it pinned the bug: `pruneStaleAfterDays: 0`
 * ("never prune") saved as 90, `maxWritesPerTurn: 0` as 10.
 */
describe("DebouncedNumberInput", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function setup(props: Partial<Parameters<typeof DebouncedNumberInput>[0]> = {}) {
    const onCommit = vi.fn();
    const utils = render(
      <DebouncedNumberInput value={90} fallback={90} onCommit={onCommit} data-testid="num" {...props} />,
    );
    return { onCommit, input: screen.getByTestId("num") as HTMLInputElement, ...utils };
  }

  it("commits a typed 0 as 0, not as the fallback", () => {
    const { onCommit, input } = setup();
    fireEvent.change(input, { target: { value: "0" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(0);
  });

  it("keeps decimals (maxCostPerRun, step 0.01)", () => {
    const { onCommit, input } = setup({ value: 5, fallback: 5, step: 0.01 });
    fireEvent.change(input, { target: { value: "0.01" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(0.01);
  });

  it("falls back only when the text is not a number at all", () => {
    const { onCommit, input } = setup();
    fireEvent.change(input, { target: { value: "" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenLastCalledWith(90);
  });

  it("clamps below a declared min instead of saving it", () => {
    const { onCommit, input } = setup({ value: 10, fallback: 10, min: 1 });
    fireEvent.change(input, { target: { value: "0" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("debounces: one commit for a burst of keystrokes", () => {
    const { onCommit, input } = setup();
    fireEvent.change(input, { target: { value: "1" } });
    fireEvent.change(input, { target: { value: "12" } });
    fireEvent.change(input, { target: { value: "120" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(120);
  });

  it("flushes a pending edit when the section collapses (unmount) instead of dropping it", () => {
    const { onCommit, input, unmount } = setup();
    fireEvent.change(input, { target: { value: "30" } });
    unmount();
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(30);
  });

  it("flushes on blur", () => {
    const { onCommit, input } = setup();
    fireEvent.change(input, { target: { value: "7" } });
    fireEvent.blur(input);
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(7);
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it("does not commit on unmount when nothing is pending", () => {
    const { onCommit, unmount } = setup();
    unmount();
    expect(onCommit).not.toHaveBeenCalled();
  });
});

describe("DebouncedInput", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("flushes a pending edit on unmount", () => {
    const onCommit = vi.fn();
    const { unmount } = render(<DebouncedInput value="a" onCommit={onCommit} data-testid="txt" />);
    fireEvent.change(screen.getByTestId("txt"), { target: { value: "0 4 * * *" } });
    unmount();
    expect(onCommit).toHaveBeenCalledExactlyOnceWith("0 4 * * *");
  });
});

describe("debounced inputs — review follow-ups", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("commits a pending edit to the target it was typed for when the input is reused for another", () => {
    const saveA = vi.fn();
    const saveB = vi.fn();
    const { rerender } = render(
      <DebouncedInput value="a-name" onCommit={saveA} commitKey="agent-a" data-testid="txt" />,
    );
    fireEvent.change(screen.getByTestId("txt"), { target: { value: "typed for A" } });
    // In-place navigation to agent B before the debounce fires.
    rerender(<DebouncedInput value="b-name" onCommit={saveB} commitKey="agent-b" data-testid="txt" />);
    act(() => vi.advanceTimersByTime(600));

    expect(saveA).toHaveBeenCalledExactlyOnceWith("typed for A");
    expect(saveB).not.toHaveBeenCalled();
  });

  it("shows the new target's value after a switch even when both targets hold the same value", () => {
    const saveA = vi.fn();
    const { rerender } = render(
      <DebouncedInput value="did:same" onCommit={saveA} commitKey="agent-a" data-testid="txt" />,
    );
    const input = screen.getByTestId("txt") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "did:draft-for-a" } });
    rerender(<DebouncedInput value="did:same" onCommit={vi.fn()} commitKey="agent-b" data-testid="txt" />);
    expect(saveA).toHaveBeenCalledExactlyOnceWith("did:draft-for-a");
    expect(input.value).toBe("did:same");
  });

  it("resets a number draft on a target switch too", () => {
    const { rerender } = render(
      <DebouncedNumberInput value={10} onCommit={vi.fn()} commitKey="agent-a" data-testid="num" />,
    );
    const input = screen.getByTestId("num") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "42" } });
    rerender(<DebouncedNumberInput value={10} onCommit={vi.fn()} commitKey="agent-b" data-testid="num" />);
    expect(input.value).toBe("10");
  });

  it("still uses the newest callback for the same target (a save moved it to a new version)", () => {
    const v1 = vi.fn();
    const v2 = vi.fn();
    const { rerender } = render(<DebouncedInput value="x" onCommit={v1} commitKey="agent-a" data-testid="txt" />);
    fireEvent.change(screen.getByTestId("txt"), { target: { value: "y" } });
    rerender(<DebouncedInput value="x" onCommit={v2} commitKey="agent-a" data-testid="txt" />);
    act(() => vi.advanceTimersByTime(600));
    expect(v1).not.toHaveBeenCalled();
    expect(v2).toHaveBeenCalledExactlyOnceWith("y");
  });

  it("applies min to the fallback too: a cleared min=1 field never commits 0", () => {
    const onCommit = vi.fn();
    render(<DebouncedNumberInput value={5} min={1} onCommit={onCommit} data-testid="num" />);
    fireEvent.change(screen.getByTestId("num"), { target: { value: "" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(1);
  });

  it("shows the value it committed when clamping changed it", () => {
    const onCommit = vi.fn();
    // The parent keeps value at 1 — nothing from outside corrects the text.
    render(<DebouncedNumberInput value={1} min={1} onCommit={onCommit} data-testid="num" />);
    const input = screen.getByTestId("num") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "0" } });
    act(() => vi.advanceTimersByTime(600));
    expect(onCommit).toHaveBeenCalledExactlyOnceWith(1);
    expect(input.value).toBe("1");
  });
});
