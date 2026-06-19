import { renderHook, act } from "@testing-library/react";
import { useDebounce } from "@/hooks/use-debounce";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
describe("useDebounce", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("returns initial value immediately", () => {
    const { result } = renderHook(() => useDebounce("hello"));
    expect(result.current).toBe("hello");
  });

  it("does not update debounced value before delay", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 400),
      { initialProps: { value: "initial" } }
    );
    expect(result.current).toBe("initial");

    rerender({ value: "updated" });
    vi.advanceTimersByTime(200);
    expect(result.current).toBe("initial");
  });

  it("updates debounced value after delay", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 400),
      { initialProps: { value: "initial" } }
    );

    rerender({ value: "updated" });
    act(() => {
      vi.advanceTimersByTime(400);
    });
    expect(result.current).toBe("updated");
  });

  it("resets timer on rapid changes", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 400),
      { initialProps: { value: "a" } }
    );

    rerender({ value: "b" });
    vi.advanceTimersByTime(200);
    rerender({ value: "c" });
    vi.advanceTimersByTime(200);
    // Only 200ms since last change, shouldn't update yet
    expect(result.current).toBe("a");

    act(() => {
      vi.advanceTimersByTime(200);
    });
    // Now 400ms after "c", should update
    expect(result.current).toBe("c");
  });

  it("uses default delay of 400ms", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value),
      { initialProps: { value: "initial" } }
    );

    rerender({ value: "updated" });
    vi.advanceTimersByTime(399);
    expect(result.current).toBe("initial");

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current).toBe("updated");
  });

  it("works with number values", () => {
    const { result, rerender } = renderHook(
      ({ value }) => useDebounce(value, 200),
      { initialProps: { value: 0 } }
    );

    rerender({ value: 42 });
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe(42);
  });
});
