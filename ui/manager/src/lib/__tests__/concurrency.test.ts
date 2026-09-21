import { describe, it, expect } from "vitest";
import { mapWithConcurrency } from "../concurrency";

describe("mapWithConcurrency", () => {
  it("never exceeds the limit in flight", async () => {
    let inFlight = 0;
    let peak = 0;
    await mapWithConcurrency(Array.from({ length: 50 }, (_, i) => i), 8, async (n) => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      await new Promise((r) => setTimeout(r, 1));
      inFlight--;
      return n;
    });
    expect(peak).toBeLessThanOrEqual(8);
    expect(peak).toBeGreaterThan(1); // ...and it does actually parallelise
  });

  it("keeps INPUT order even when completion order differs", async () => {
    // Callers zip results against the input by index; returning completion
    // order would silently mis-attribute every status to the wrong agent.
    const out = await mapWithConcurrency([30, 20, 10], 3, async (ms) => {
      await new Promise((r) => setTimeout(r, ms));
      return ms;
    });
    expect(out).toEqual([30, 20, 10]);
  });

  it("passes the index through", async () => {
    expect(await mapWithConcurrency(["a", "b"], 1, async (v, i) => `${i}:${v}`)).toEqual([
      "0:a",
      "1:b",
    ]);
  });

  it("handles an empty list and a limit larger than the input", async () => {
    expect(await mapWithConcurrency([], 8, async () => 1)).toEqual([]);
    expect(await mapWithConcurrency([1], 100, async (n) => n * 2)).toEqual([2]);
  });

  it("clamps a nonsensical limit rather than hanging", async () => {
    // A limit of 0 would spawn no workers and never resolve.
    expect(await mapWithConcurrency([1, 2], 0, async (n) => n)).toEqual([1, 2]);
  });

  it("rejects when the mapper throws — a systemic failure must not look like empty data", async () => {
    await expect(
      mapWithConcurrency([1, 2, 3], 2, async (n) => {
        if (n === 2) throw new Error("boom");
        return n;
      }),
    ).rejects.toThrow("boom");
  });
});
