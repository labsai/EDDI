import { describe, expect, it } from "vitest";
import { sparklineGeometry } from "../sparkline-geometry";

const pt = (day: string, value: number | null) => ({ day, value, n: value === null ? 0 : 10 });

describe("sparklineGeometry", () => {
  it("draws a gap, not a zero, for a day without data", () => {
    const g = sparklineGeometry([pt("2026-11-01", 1), pt("2026-11-02", null), pt("2026-11-03", 3), pt("2026-11-04", 2)]);
    expect(g.filled).toBe(3);
    // The null day breaks the line: the path starts a second subpath with M.
    expect(g.path.match(/M/g)).toHaveLength(2);
    expect(g.min).toBe(1);
    expect(g.max).toBe(3);
  });

  it("places a marker on its day and drops one outside the period", () => {
    const g = sparklineGeometry(
      [pt("2026-11-01", 1), pt("2026-11-02", 2), pt("2026-11-03", 3)],
      [
        { id: "in", at: "2026-11-02T10:00:00Z", kind: "DEPLOYMENT", label: "v8" },
        { id: "out", at: "2026-10-01T10:00:00Z", kind: "NOTE", label: "old" },
      ],
    );
    expect(g.markers.map((m) => m.id)).toEqual(["in"]);
    expect(g.markers[0]!.x).toBeGreaterThan(3);
  });

  it("copes with a flat series", () => {
    const g = sparklineGeometry([pt("2026-11-01", 5), pt("2026-11-02", 5)]);
    expect(g.path).not.toContain("NaN");
  });
});
