import { describe, expect, it } from "vitest";
import { getAgentHealth, getHealthSeries, getHealthSessions, getPortfolio } from "@/components/health/api";
import { HEALTH_AGENTS, MOCK_TODAY } from "../health-fixtures";

/** The health mocks answer the way planning/agent-health-api.md says the backend does, so tests built on them hold against it. */
describe("health handlers", () => {
  it.each([
    ["7d", 7, "2026-11-04", "2026-10-28"],
    ["30d", 30, "2026-10-12", "2026-09-12"],
    ["90d", 90, "2026-08-13", "2026-05-15"],
  ] as const)("derives the summary's and the series' range from period=%s", async (period, count, from, baselineFrom) => {
    const health = await getAgentHealth(HEALTH_AGENTS.healthy, { period });
    expect(health.period).toEqual({ from, to: MOCK_TODAY, days: count });
    expect(health.baseline.from).toBe(baselineFrom);
    const series = await getHealthSeries(HEALTH_AGENTS.healthy, ["agentFailureRate"], { period });
    expect(series.period).toEqual(health.period);
    const points = series.series[0]!.points;
    expect(points).toHaveLength(count);
    expect(points[0]!.day).toBe(from);
    expect(points[points.length - 1]!.day).toBe(MOCK_TODAY);
  });

  it("filters sessions by version before paging", async () => {
    const page = await getHealthSessions(HEALTH_AGENTS.healthy, { version: 7 });
    expect(page.items.map((r) => r.agentVersion)).toEqual([7]);
  });

  it("lists a session without an outcome for its origin, but not under an outcome or evidence filter", async () => {
    const byOrigin = await getHealthSessions(HEALTH_AGENTS.healthy, { origins: ["schedule"] });
    expect(byOrigin.items.map((r) => r.conversationId)).toEqual(["conv-ee"]);
    const withOutcome = await getHealthSessions(HEALTH_AGENTS.healthy, { origins: ["schedule"], outcome: ["RESOLVED"] });
    expect(withOutcome.items).toEqual([]);
    const withEvidence = await getHealthSessions(HEALTH_AGENTS.healthy, { origins: ["schedule"], evidence: ["silence"] });
    expect(withEvidence.items).toEqual([]);
  });

  it("returns no H3-confirmed session on evidence the backend cannot record yet", async () => {
    const page = await getHealthSessions(HEALTH_AGENTS.healthy, { evidence: ["external_event", "conversation_id_event"] });
    expect(page.items).toEqual([]);
  });

  it("sorts the portfolio by name and by traffic before paging", async () => {
    const byName = await getPortfolio({ sort: "name" });
    const names = byName.items.map((i) => i.agentName);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    expect(names[0]).toBe("Brand New Agent");
    expect(byName.nextCursor).toBe("3");

    const byTraffic = await getPortfolio({ sort: "traffic" });
    expect(byTraffic.items.map((i) => i.sessions)).toEqual([1820, 1820, 640]);
    const last = await getPortfolio({ sort: "traffic", cursor: "3" });
    // Sessions that cannot be counted come last.
    expect(last.items.map((i) => i.sessions)).toEqual([90, 12, null]);
  });
});
