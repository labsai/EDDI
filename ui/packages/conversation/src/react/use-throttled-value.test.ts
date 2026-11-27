import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useThrottledValue } from "./use-throttled-value";

describe("useThrottledValue", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("shows each update at most once per interval while active, and the last one after it", () => {
    const { result, rerender } = renderHook(({ v }) => useThrottledValue(v, 50, true), {
      initialProps: { v: "a" },
    });
    expect(result.current).toBe("a");
    vi.advanceTimersByTime(60);
    rerender({ v: "ab" });
    expect(result.current).toBe("ab"); // leading edge: after a quiet spell, at once
    rerender({ v: "abc" });
    rerender({ v: "abcd" });
    expect(result.current).toBe("ab"); // inside the window: held back
    act(() => {
      vi.advanceTimersByTime(60);
    });
    expect(result.current).toBe("abcd"); // trailing edge: the newest text
  });

  it("returns the real value at once when the stream ends", () => {
    const { result, rerender } = renderHook(({ v, active }) => useThrottledValue(v, 50, active), {
      initialProps: { v: "a", active: true },
    });
    vi.advanceTimersByTime(60);
    rerender({ v: "ab", active: true });
    rerender({ v: "abc", active: true });
    expect(result.current).toBe("ab");
    rerender({ v: "abc", active: false });
    expect(result.current).toBe("abc");
  });

  it("does not throttle at all when inactive", () => {
    const { result, rerender } = renderHook(({ v }) => useThrottledValue(v, 50, false), {
      initialProps: { v: "a" },
    });
    rerender({ v: "b" });
    rerender({ v: "c" });
    expect(result.current).toBe("c");
  });
});
