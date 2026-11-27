import { describe, expect, it } from "vitest";
import { getHealthSeries, getHealthSessions } from "@eddi/app-kit/lib/api/health";
import { HEALTH_AGENTS, compareFor, measureValue } from "@eddi/app-kit/test/mocks/health-fixtures";

// The mock API is what every health test runs against: a mock that contradicts
// itself lets a test pass on data the real backend can never send.
describe("health mocks", () => {
  it("answers a series with the bounds of the days it covers", async () => {
    const week = await getHealthSeries(HEALTH_AGENTS.healthy, ["sessions"], { period: "7d" });
    const days = week.series[0]!.points.map((p) => p.day);
    expect(week.period).toEqual({ from: days[0], to: days[days.length - 1], days: 7 });
    expect(week.period.from).toBe("2026-11-04");

    const month = await getHealthSeries(HEALTH_AGENTS.healthy, ["sessions"], { period: "30d" });
    expect(month.period).toEqual({ from: "2026-10-12", to: "2026-11-10", days: 30 });

    const quarter = await getHealthSeries(HEALTH_AGENTS.healthy, ["sessions"], { period: "90d" });
    expect(quarter.period.from).toBe(quarter.series[0]!.points[0]!.day);
  });

  it("derives every compare row from the two sides it returns", () => {
    const c = compareFor(HEALTH_AGENTS.healthy, 7, 8);
    for (const d of c.deltas) {
      expect(d.from).toBe(measureValue(c.from.sections, d.measureId));
      expect(d.to).toBe(measureValue(c.to.sections, d.measureId));
      expect(d.delta).toBeCloseTo(d.to! - d.from!, 9);
      // Every compared measure really changed, so the verdicts rest on a change.
      expect(d.delta).not.toBe(0);
    }
  });

  it("filters sessions by version, and lists every version without one", async () => {
    const v7 = await getHealthSessions(HEALTH_AGENTS.healthy, { version: 7 });
    expect(v7.items.map((r) => r.conversationId)).toEqual(["conv-dd"]);
    const all = await getHealthSessions(HEALTH_AGENTS.healthy, {});
    expect(all.items.length).toBeGreaterThan(1);
  });
});
