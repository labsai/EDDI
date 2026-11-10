import type {
  AgentHealth,
  HealthCompare,
  HealthMarker,
  HealthSection,
  HealthSessionRow,
  Measure,
  Portfolio,
  PortfolioItem,
  Series,
  SeriesPoint,
} from "@/lib/api/health";

/**
 * Fixtures for the Agent Health mocks, shaped like planning/agent-health-api.md.
 * Each scenario is keyed by an agent id (see {@link HEALTH_AGENTS}); any other
 * id answers the healthy agent, so a page that links to "agent1" still works.
 */

export const HEALTH_AGENTS = {
  healthy: "health-healthy",
  attention: "health-attention",
  noData: "health-nodata",
  restricted: "health-restricted",
  preH4: "health-preh4",
  silence: "health-silence",
  noReview: "health-noreview",
  forbidden: "health-forbidden",
} as const;

export function measure(partial: Partial<Measure> & Pick<Measure, "id" | "label" | "unit" | "value">): Measure {
  return {
    kind: "SCORED",
    critical: false,
    n: 1200,
    interval: null,
    baseline: null,
    delta: null,
    significant: false,
    band: "GREEN",
    thresholds: null,
    breakdown: null,
    hint: null,
    ...partial,
  };
}

const lower = (green: number, red: number) => ({ green, red, higherIsBetter: false });
const higher = (green: number, red: number) => ({ green, red, higherIsBetter: true });

function reliability(over: Record<string, Partial<Measure>> = {}): HealthSection {
  return {
    restricted: false,
    measures: [
      measure({ id: "agentFailureRate", label: "Agent-caused failures", unit: "ratio", value: 0.004, n: 4210, interval: { low: 0.002, high: 0.007 }, baseline: 0.005, delta: -0.001, critical: true, thresholds: lower(0.01, 0.03), ...over.agentFailureRate }),
      measure({ id: "platformFailureRate", label: "Platform failures", kind: "INFORMATIONAL", unit: "ratio", value: 0.011, n: 4210, band: "GREY", ...over.platformFailureRate }),
      measure({ id: "apdex", label: "Apdex", unit: "score", value: 0.93, n: 4210, thresholds: higher(0.85, 0.7), ...over.apdex }),
      measure({ id: "turnDurationP95", label: "Turn duration (p95)", unit: "ms", value: 3200, n: 4210, baseline: 3100, delta: 100, thresholds: lower(4000, 8000), ...over.turnDurationP95 }),
    ],
  };
}

function effectiveness(over: Record<string, Partial<Measure>> = {}, coverage = 0.82): HealthSection {
  return {
    restricted: false,
    coverage,
    measures: [
      measure({
        id: "resolutionRate", label: "Resolution", unit: "ratio", value: 0.71, n: 1820, critical: true,
        interval: { low: 0.69, high: 0.73 }, baseline: 0.68, delta: 0.03, significant: true, thresholds: higher(0.7, 0.4),
        breakdown: [
          { key: "confirmed", label: "Confirmed", value: 0.39, n: 710 },
          { key: "agentClosed", label: "Agent closed", value: 0.2, n: 364 },
          { key: "silence", label: "Silence", value: 0.12, n: 218 },
        ],
        ...over.resolutionRate,
      }),
      measure({ id: "escalationRate", label: "Escalation", unit: "ratio", value: 0.08, n: 1820, thresholds: lower(0.1, 0.25), ...over.escalationRate }),
      measure({ id: "abandonmentRate", label: "Abandonment", unit: "ratio", value: 0.12, n: 1820, band: "AMBER", thresholds: lower(0.1, 0.25), ...over.abandonmentRate }),
      measure({ id: "outcomeCoverage", label: "Outcome coverage", unit: "ratio", value: coverage, n: 1820, thresholds: higher(0.5, 0.2), band: coverage >= 0.5 ? "GREEN" : "RED", ...over.outcomeCoverage }),
    ],
  };
}

function quality(): HealthSection {
  return {
    restricted: false,
    measures: [
      measure({ id: "ratingScore", label: "Rating", unit: "score", value: 0.84, n: 96, thresholds: higher(0.8, 0.6) }),
      measure({ id: "guardrailTripRate", label: "Guardrail trips", kind: "INFORMATIONAL", unit: "ratio", value: 0.004, n: 4210, band: "GREY" }),
    ],
  };
}

function efficiency(): HealthSection {
  return {
    restricted: false,
    measures: [
      measure({ id: "costPerSession", label: "Cost per session", unit: "usd", value: 0.031, n: 1820, thresholds: lower(0.05, 0.2) }),
      measure({ id: "costPerResolvedSession", label: "Cost per resolved session", unit: "usd", value: 0.044, n: 1292, thresholds: lower(0.5, 1) }),
      measure({ id: "tokensPerTurn", label: "Tokens per turn", unit: "tokens", value: 1850, n: 4210, kind: "INFORMATIONAL", band: "GREY" }),
    ],
  };
}

function adoption(): HealthSection {
  return {
    restricted: false,
    measures: [
      measure({ id: "sessions", label: "Sessions", unit: "count", value: 1820, n: 1820, kind: "INFORMATIONAL", band: "GREY", baseline: 1980, delta: -160 }),
      measure({ id: "distinctUsers", label: "Distinct users", unit: "count", value: 940, n: 940, kind: "INFORMATIONAL", band: "GREY" }),
    ],
  };
}

function business(): HealthSection {
  return {
    restricted: false,
    measures: [
      measure({ id: "event:order.placed", label: "order.placed", unit: "count", value: 412, n: 412, kind: "INFORMATIONAL", band: "GREY" }),
    ],
  };
}

const baseHealth = (agentId: string, agentName: string): AgentHealth => ({
  agentId,
  agentName,
  period: { from: "2026-10-12", to: "2026-11-10", days: 30 },
  baseline: { from: "2026-09-12", to: "2026-10-11" },
  version: null,
  origins: ["api", "v1", "email"],
  status: "HEALTHY",
  reasons: [],
  preset: "support",
  generatedAt: "2026-11-10T09:00:00Z",
  dataThrough: "2026-11-10",
  sections: {
    reliability: reliability(),
    effectiveness: effectiveness(),
    quality: quality(),
    efficiency: efficiency(),
    adoption: adoption(),
    business: business(),
  },
  checks: [{ id: "goldenSuite", label: "Golden conversations", state: "PASS", at: "2026-11-08T12:00:00Z", stale: false, version: 8 }],
});

export function healthFor(agentId: string): AgentHealth {
  switch (agentId) {
    case HEALTH_AGENTS.attention: {
      const h = baseHealth(agentId, "Order Bot");
      h.status = "NEEDS_ATTENTION";
      h.reasons = [
        { measureId: "resolutionRate", severity: "RED", text: "Resolution 41 % (−12 pp since v8 on 3 Nov)", markerId: "m-123" },
        { measureId: "agentFailureRate", severity: "AMBER", text: "Agent-caused failures 1.2 % (+0.8 pp)", markerId: null },
      ];
      h.sections.reliability = reliability({
        agentFailureRate: { value: 0.012, delta: 0.008, baseline: 0.004, significant: true, band: "AMBER", interval: { low: 0.009, high: 0.016 } },
      });
      h.sections.effectiveness = effectiveness({
        resolutionRate: {
          value: 0.41, baseline: 0.53, delta: -0.12, significant: true, band: "RED",
          breakdown: [
            { key: "confirmed", label: "Confirmed", value: 0.2, n: 360 },
            { key: "agentClosed", label: "Agent closed", value: 0.11, n: 200 },
            { key: "silence", label: "Silence", value: 0.1, n: 180 },
          ],
        },
      });
      h.checks = [{ id: "goldenSuite", label: "Golden conversations", state: "FAIL", at: "2026-10-20T12:00:00Z", stale: true, version: 7 }];
      return h;
    }
    case HEALTH_AGENTS.noData: {
      const h = baseHealth(agentId, "Brand New Agent");
      h.status = "NOT_ENOUGH_DATA";
      const grey = (m: Measure): Measure => ({ ...m, value: null, n: 4, band: "GREY", interval: null, delta: null, baseline: null, significant: false, hint: "Fewer than 30 engaged sessions" });
      for (const id of ["reliability", "effectiveness", "efficiency"] as const) {
        h.sections[id] = { ...h.sections[id]!, coverage: id === "effectiveness" ? null : undefined, measures: h.sections[id]!.measures.map(grey) };
      }
      h.checks = [];
      return h;
    }
    case HEALTH_AGENTS.restricted: {
      const h = baseHealth(agentId, "Viewer-only Agent");
      h.sections.quality = { restricted: true, measures: [] };
      return h;
    }
    case HEALTH_AGENTS.preH4: {
      const h = baseHealth(agentId, "Pre-status Agent");
      h.status = null;
      h.reasons = [];
      return h;
    }
    case HEALTH_AGENTS.silence: {
      const h = baseHealth(agentId, "Quiet Resolver");
      h.status = "WATCH";
      h.reasons = [{ measureId: "resolutionRate", severity: "AMBER", text: "Resolution rate is mostly assumed from silence", markerId: null }];
      h.sections.effectiveness = effectiveness({
        resolutionRate: {
          value: 0.7, band: "AMBER",
          breakdown: [
            { key: "confirmed", label: "Confirmed", value: 0.1, n: 180 },
            { key: "agentClosed", label: "Agent closed", value: 0.1, n: 180 },
            { key: "silence", label: "Silence", value: 0.5, n: 910 },
          ],
        },
      });
      return h;
    }
    default:
      return baseHealth(agentId, agentId === HEALTH_AGENTS.healthy ? "Support" : "Support");
  }
}

// ── Series and markers ────────────────────────────────────────────

export const MARKERS: HealthMarker[] = [
  { id: "m-123", at: "2026-11-03T10:12:00Z", kind: "DEPLOYMENT", label: "v8 deployed (production)", version: 8 },
  { id: "m-124", at: "2026-10-25T08:00:00Z", kind: "SNIPPET_CHANGE", label: "Snippet \"tone\" edited" },
];

export function days(count: number, end = "2026-11-10"): string[] {
  const last = Date.parse(`${end}T00:00:00Z`);
  return Array.from({ length: count }, (_, i) => new Date(last - (count - 1 - i) * 86_400_000).toISOString().slice(0, 10));
}

export function seriesFor(measureId: string, base: number, unit: Series["unit"], count = 30): Series {
  const points: SeriesPoint[] = days(count).map((day, i) => {
    // One empty day, as the contract promises ("a point with value null, n 0").
    if (i === 7) return { day, value: null, n: 0 };
    const wobble = Math.sin(i / 3) * 0.1 + (i > 22 ? -0.15 : 0);
    return { day, value: Math.max(0, base * (1 + wobble)), n: 100 + i };
  });
  return { measure: measureId, group: null, unit, points };
}

// ── Compare, portfolio, sessions ──────────────────────────────────

export function compareFor(agentId: string, from: number, to: number): HealthCompare {
  const a = healthFor(HEALTH_AGENTS.healthy);
  const b = healthFor(HEALTH_AGENTS.attention);
  return {
    agentId,
    from: { version: from, window: { from: "2026-10-12", to: "2026-10-25" }, sections: a.sections },
    to: { version: to, window: { from: "2026-10-26", to: "2026-11-10" }, sections: b.sections },
    deltas: [
      { measureId: "resolutionRate", from: 0.53, to: 0.41, delta: -0.12, significant: true, better: false },
      { measureId: "agentFailureRate", from: 0.004, to: 0.012, delta: 0.008, significant: true, better: false },
      { measureId: "costPerSession", from: 0.034, to: 0.031, delta: -0.003, significant: true, better: true },
      { measureId: "turnDurationP95", from: 3100, to: 3200, delta: 100, significant: false, better: null },
      { measureId: "sessions", from: 900, to: 920, delta: 20, significant: true, better: null },
    ],
  };
}

function item(agentId: string, agentName: string, partial: Partial<PortfolioItem>): PortfolioItem {
  const h = healthFor(agentId);
  return {
    agentId,
    agentName,
    status: h.status,
    preset: "support",
    topReason: h.reasons[0] ?? null,
    sessions: 1820,
    sessionsDelta: -0.08,
    keyMeasures: [
      h.sections.effectiveness!.measures[0]!,
      h.sections.reliability!.measures[0]!,
      h.sections.efficiency!.measures[1]!,
    ],
    platformIncident: false,
    ...partial,
  };
}

export function portfolioFixture(): Portfolio {
  return {
    items: [
      item(HEALTH_AGENTS.attention, "Order Bot", { platformIncident: true }),
      item(HEALTH_AGENTS.silence, "Quiet Resolver", { sessions: 640, sessionsDelta: 0.12 }),
      item(HEALTH_AGENTS.healthy, "Support", {}),
      item(HEALTH_AGENTS.preH4, "Pre-status Agent", { sessions: 90, sessionsDelta: null }),
      item(HEALTH_AGENTS.noData, "Brand New Agent", { sessions: 12, sessionsDelta: null, keyMeasures: [] }),
    ],
    platformIncidents: [{ from: "2026-11-08T02:00:00Z", to: "2026-11-08T03:30:00Z", failureClass: "RATE_LIMITED", agents: 12 }],
    nextCursor: null,
  };
}

export const SESSION_ROWS: HealthSessionRow[] = [
  { conversationId: "conv-aa", sessionNo: 0, startedAt: "2026-11-09T10:00:00Z", endedAt: "2026-11-09T10:05:00Z", agentVersion: 8, origin: "api", engaged: true, outcome: "ABANDONED", evidence: "inferred", provisional: false, turns: 6, costUsd: 0.031, failures: 1 },
  { conversationId: "conv-bb", sessionNo: 0, startedAt: "2026-11-09T11:00:00Z", endedAt: "2026-11-09T11:08:00Z", agentVersion: 8, origin: "v1", engaged: true, outcome: "ASSUMED_RESOLVED", evidence: "silence", provisional: false, turns: 4, costUsd: 0.02, failures: 0 },
  { conversationId: "conv-cc", sessionNo: 1, startedAt: "2026-11-09T12:00:00Z", endedAt: null, agentVersion: 8, origin: "email", engaged: true, outcome: "RESOLVED", evidence: "external_event", provisional: true, turns: 9, costUsd: null, failures: 0 },
  { conversationId: "conv-dd", sessionNo: 0, startedAt: "2026-11-09T13:00:00Z", endedAt: "2026-11-09T13:02:00Z", agentVersion: 7, origin: "api", engaged: true, outcome: "ASSUMED_RESOLVED", evidence: "agent_closed", provisional: false, turns: 3, costUsd: 0.011, failures: 0 },
];
