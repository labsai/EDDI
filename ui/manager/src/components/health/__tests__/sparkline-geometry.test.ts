import { describe, expect, it } from "vitest";
import { sparklineGeometry } from "../sparkline-geometry";

const pt = (day: string, value: number | null) => ({ day, value, n: value === null ? 0 : 10 });

describe("sparklineGeometry", () => {
  it("draws a gap, not a zero, for a day without data", () => {
    const g = sparklineGeometry([pt("2026-11-01", 1), pt("2026-11-02", null), pt("2026-11-03", 3), pt("2026-11-04", 2)]);
    expect(g.filled).toBe(3);
    // The null day breaks the line: the day before it stands alone (a dot), the two after it are a line.
    expect(g.path.match(/M/g)).toHaveLength(1);
    expect(g.path.match(/L/g)).toHaveLength(1);
    expect(g.dots).toHaveLength(1);
    expect(g.min).toBe(1);
    expect(g.max).toBe(3);
  });

  it("draws isolated days as dots, since a lone M paints nothing", () => {
    const g = sparklineGeometry([pt("2026-11-01", 1), pt("2026-11-02", null), pt("2026-11-03", 3)]);
    expect(g.filled).toBe(2);
    expect(g.path).toBe("");
    expect(g.dots).toHaveLength(2);
    expect(g.dots[0]!.x).toBeLessThan(g.dots[1]!.x);
    // The lower value sits lower (a larger y).
    expect(g.dots[0]!.y).toBeGreaterThan(g.dots[1]!.y);
  });

  it("draws no dot for a day that belongs to a line", () => {
    const g = sparklineGeometry([pt("2026-11-01", 1), pt("2026-11-02", 2), pt("2026-11-03", 3)]);
    expect(g.dots).toEqual([]);
    expect(g.path.match(/L/g)).toHaveLength(2);
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

  it("draws a day with data between two gaps as a dot, not an invisible move", () => {
    const g = sparklineGeometry([pt("2026-11-01", 1), pt("2026-11-02", null), pt("2026-11-03", 2)]);
    expect(g.filled).toBe(2);
    // Two isolated days: no line to draw, so each is a dot (the sparkline strokes it with a round cap).
    expect(g.path).toBe("");
    expect(g.dots).toHaveLength(2);
    // A connected run stays a line, with no dot.
    const run = sparklineGeometry([pt("2026-11-01", 1), pt("2026-11-02", 2)]);
    expect(run.path).toMatch(/^M[\d.]+ [\d.]+L[\d.]+ [\d.]+$/);
    expect(run.dots).toHaveLength(0);
  });

  it("copes with a flat series", () => {
    const g = sparklineGeometry([pt("2026-11-01", 5), pt("2026-11-02", 5)]);
    expect(g.path).not.toContain("NaN");
  });
});
