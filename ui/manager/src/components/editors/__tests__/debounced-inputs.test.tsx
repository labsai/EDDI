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
