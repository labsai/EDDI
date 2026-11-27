import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { createInvalidationCoalescer } from "@eddi/app-kit/lib/coalesce-invalidations";

/** A burst of events refetches each query key at most once per window. */
describe("createInvalidationCoalescer", () => {
  const WINDOW = 500;
  let run: Mock<(queryKey: readonly unknown[]) => void>;

  beforeEach(() => {
    vi.useFakeTimers();
    run = vi.fn<(queryKey: readonly unknown[]) => void>();
  });
  afterEach(() => vi.useRealTimers());

  it("runs an isolated invalidation immediately and nothing after it", () => {
    const c = createInvalidationCoalescer(run, WINDOW);
    c.invalidate(["a"]);
    expect(run).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(WINDOW * 3);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it("collapses a burst into the leading run plus one trailing run", () => {
    const c = createInvalidationCoalescer(run, WINDOW);
    for (let i = 0; i < 20; i++) c.invalidate(["a"]);
    expect(run).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(WINDOW);
    expect(run).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(WINDOW * 3);
    expect(run).toHaveBeenCalledTimes(2);
  });

  it("keeps refreshing a continuous stream once per window", () => {
    const c = createInvalidationCoalescer(run, WINDOW);
    // One event every 50 ms for 2 s = 40 events.
    for (let i = 0; i < 40; i++) {
      c.invalidate(["a"]);
      vi.advanceTimersByTime(50);
    }
    // Leading run + one per elapsed window; far fewer than 40, and not starved.
    expect(run.mock.calls.length).toBeGreaterThanOrEqual(4);
    expect(run.mock.calls.length).toBeLessThanOrEqual(6);
  });

  it("coalesces per key: different keys do not delay one another", () => {
    const c = createInvalidationCoalescer(run, WINDOW);
    c.invalidate(["a"]);
    c.invalidate(["b"]);
    c.invalidate(["a", "x"]);
    expect(run.mock.calls.map((call) => JSON.stringify(call[0]))).toEqual(['["a"]', '["b"]', '["a","x"]']);
  });

  it("dispose cancels a pending trailing run", () => {
    const c = createInvalidationCoalescer(run, WINDOW);
    c.invalidate(["a"]);
    c.invalidate(["a"]);
    c.dispose();
    vi.advanceTimersByTime(WINDOW * 3);
    expect(run).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });
});
