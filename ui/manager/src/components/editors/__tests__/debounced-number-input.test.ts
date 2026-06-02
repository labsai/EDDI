import { describe, it, expect } from "vitest";

/**
 * Unit test for the parseFloat-based number parsing used by DebouncedNumberInput.
 *
 * This validates the fix where parseInt was replaced with parseFloat to preserve
 * decimal precision for fields like maxCostPerRun (step=0.01).
 */
describe("DebouncedNumberInput — parseFloat logic", () => {
  // This mirrors the exact logic from agent-config-sections.tsx line 95:
  //   onCommit(parseFloat(raw) || fallback)
  function parseNumber(raw: string, fallback: number): number {
    return parseFloat(raw) || fallback;
  }

  it("preserves decimal precision (maxCostPerRun scenario)", () => {
    expect(parseNumber("5.50", 5)).toBe(5.5);
    expect(parseNumber("0.01", 5)).toBe(0.01);
    expect(parseNumber("3.14", 5)).toBe(3.14);
    expect(parseNumber("12.99", 5)).toBe(12.99);
  });

  it("handles integer values correctly", () => {
    expect(parseNumber("50", 10)).toBe(50);
    expect(parseNumber("100", 10)).toBe(100);
    expect(parseNumber("1", 10)).toBe(1);
  });

  it("falls back on empty or invalid input", () => {
    expect(parseNumber("", 5)).toBe(5);
    expect(parseNumber("abc", 5)).toBe(5);
    expect(parseNumber("   ", 5)).toBe(5);
  });

  it("falls back on zero input (intentional — these are positive config values)", () => {
    // 0 || fallback = fallback — this is the intended behavior for config fields
    // like maxRecallEntries, batchSize, etc. that should never be zero.
    expect(parseNumber("0", 5)).toBe(5);
  });

  // Regression: parseInt("5.50", 10) would return 5, losing the decimal part
  it("does NOT truncate decimals like parseInt would", () => {
    const parseIntResult = parseInt("5.50", 10);
    const parseFloatResult = parseFloat("5.50");

    expect(parseIntResult).toBe(5); // parseInt truncates!
    expect(parseFloatResult).toBe(5.5); // parseFloat preserves!
    expect(parseNumber("5.50", 5)).toBe(5.5); // Our function preserves
  });
});
