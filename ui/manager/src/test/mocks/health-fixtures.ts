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
} from "@/components/health/api";

/**
 * Fixtures for the Agent Health mocks, shaped like planning/agent-health-api.md.
 * Each scenario is keyed by an agent id (see {@link HEALTH_AGENTS}); any other
 * id answers the healthy agent, so a page that links to "agent1" still works.
 *
 * The default is what the H3 backend sends: `status: null` for every agent (the
 * status arrives with H4). Only the scenarios marked "H4" carry a status, to
 * exercise the ring, the reasons and the portfolio badges ahead of that phase.
 */

export const HEALTH_AGENTS = {
  healthy: "health-healthy",
  /** H4: the healthy agent with the status the backend sends from H4 on. */
  healthyH4: "health-healthy-h4",
  /** H4: a status of NEEDS_ATTENTION with its reasons. */
  attention: "health-attention",
  noData: "health-nodata",
  restricted: "health-restricted",
  /** No status, as every agent until H4 (the default); kept as a named scenario for the portfolio. */
  preH4: "health-preh4",
  /** H4: a WATCH status for a resolution rate mostly assumed from silence. */
  silence: "health-silence",
  noReview: "health-noreview",
  forbidden: "health-forbidden",
  /** Sessions come one per page, so paging can be exercised. */
  paged: "health-paged",
  /** The whole health endpoint answers 403. */
  denied: "health-denied",
  /** The health endpoint answers 404 (unknown agent). */
  missing: "health-missing",
  /** Outcome coverage is below the minimum: the rates are withheld (null) but `n` stays. */
  lowCoverage: "health-lowcoverage",
  /** The outcome evaluation is catching up: values are kept and carry a hint. */
  evaluating: "health-evaluating",
  /** Unknown section, unknown measure id, null value with a delta. */
  unknown: "health-unknown",
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
      // The label names the Apdex threshold T; there is no separate field for it.
      measure({ id: "apdex", label: "Response time (Apdex, T = 4 s)", unit: "score", value: 0.93, n: 4210, thresholds: higher(0.85, 0.7), ...over.apdex }),
      // No variance to test: `significant` is always false, even with a delta.
      measure({ id: "turnDurationP95", label: "Turn duration (p95)", unit: "ms", value: 3200, n: 4210, baseline: 3100, delta: 100, thresholds: lower(4000, 8000), ...over.turnDurationP95 }),
    ],
  };
}

/** The band of `outcomeCoverage` under the H3 thresholds (green ≥ 0.8, red ≤ 0.5). */
const coverageBand = (coverage: number): Measure["band"] => (coverage >= 0.8 ? "GREEN" : coverage <= 0.5 ? "RED" : "AMBER");

function effectiveness(over: Record<string, Partial<Measure>> = {}, coverage = 0.82): HealthSection {
  return {
    restricted: false,
    coverage,
    measures: [
      measure({
        id: "resolutionRate", label: "Resolution", unit: "ratio", value: 0.71, n: 1820, critical: true,
        interval: { low: 0.69, high: 0.73 }, baseline: 0.68, delta: 0.03, significant: true, thresholds: higher(0.7, 0.4),
        // Contributions to the rate: they add up to `value`.
        breakdown: [
          { key: "confirmed", label: "Confirmed", value: 0.3, n: 546 },
          { key: "selfReported", label: "Self-reported", value: 0.05, n: 91 },
          { key: "agentClosed", label: "Agent closed", value: 0.2, n: 364 },
          { key: "silence", label: "Silence", value: 0.16, n: 291 },
        ],
        ...over.resolutionRate,
      }),
      // H3: escalation and abandonment have no thresholds until health profiles (H4), so no band either.
      measure({ id: "escalationRate", label: "Escalation", unit: "ratio", value: 0.08, n: 1820, band: "GREY", ...over.escalationRate }),
      measure({ id: "abandonmentRate", label: "Abandonment", unit: "ratio", value: 0.12, n: 1820, band: "GREY", ...over.abandonmentRate }),
      // H3: green from 0.8, red at or below 0.5, amber between; never significant.
      measure({ id: "outcomeCoverage", label: "Outcome coverage", unit: "ratio", value: coverage, n: 1820, thresholds: higher(0.8, 0.5), band: coverageBand(coverage), ...over.outcomeCoverage }),
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
  // H3: no agent has a status until H4.
  status: null,
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
          value: 0.41, baseline: 0.53, delta: -0.12, significant: true, band: "RED", interval: { low: 0.38, high: 0.44 },
          breakdown: [
            { key: "confirmed", label: "Confirmed", value: 0.15, n: 273 },
            { key: "selfReported", label: "Self-reported", value: 0.03, n: 55 },
            { key: "agentClosed", label: "Agent closed", value: 0.11, n: 200 },
            { key: "silence", label: "Silence", value: 0.12, n: 218 },
          ],
        },
      });
      h.checks = [{ id: "goldenSuite", label: "Golden conversations", state: "FAIL", at: "2026-10-20T12:00:00Z", stale: true, version: 7 }];
      return h;
    }
    case HEALTH_AGENTS.healthyH4: {
      const h = baseHealth(agentId, "Support");
      h.status = "HEALTHY";
      return h;
    }
    case HEALTH_AGENTS.noData: {
      const h = baseHealth(agentId, "Brand New Agent");
      // H4: the status that says why the measures are grey.
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
    case HEALTH_AGENTS.lowCoverage: {
      const h = baseHealth(agentId, "Few Outcomes");
      const hint = "Outcome coverage is 32 %, below the minimum of 50 %";
      const withheld = (m: Measure): Measure => ({ ...m, value: null, interval: null, delta: null, baseline: null, significant: false, band: "GREY", hint });
      h.sections.effectiveness = effectiveness({}, 0.32);
      const eff = h.sections.effectiveness;
      eff.measures = eff.measures.map((m) => {
        if (m.id === "outcomeCoverage") return { ...m, value: 0.32, n: 1820, band: "RED" as const };
        const w = withheld(m);
        // Every part is null, too, but the sessions behind each source are counted.
        return m.id === "resolutionRate" ? { ...w, breakdown: (m.breakdown ?? []).map((b) => ({ ...b, value: null })) } : w;
      });
      return h;
    }
    case HEALTH_AGENTS.evaluating: {
      const h = baseHealth(agentId, "Catching Up");
      const hint = "Outcomes are still being evaluated (through 2026-11-02)";
      const eff = h.sections.effectiveness!;
      eff.measures = eff.measures.map((m) => ({ ...m, hint }));
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
          value: 0.7, band: "AMBER", interval: { low: 0.67, high: 0.73 },
          breakdown: [
            { key: "confirmed", label: "Confirmed", value: 0.08, n: 146 },
            { key: "selfReported", label: "Self-reported", value: 0.02, n: 36 },
            { key: "agentClosed", label: "Agent closed", value: 0.1, n: 180 },
            { key: "silence", label: "Silence", value: 0.5, n: 910 },
          ],
        },
      });
      return h;
    }
    case HEALTH_AGENTS.unknown: {
      const h = baseHealth(agentId, "Future Agent");
      h.sections.reliability = {
        restricted: false,
        measures: [
          // A measure id the UI has never heard of renders from its own fields.
          measure({ id: "mystery:metric", label: "Mystery metric", unit: "count", value: 42, n: 10, kind: "INFORMATIONAL", band: "GREY" }),
          // No value, but the backend still sent a delta: the UI must not show a change against nothing.
          measure({ id: "apdex", label: "Apdex", unit: "score", value: null, n: 3, band: "GREY", delta: 0.2, significant: true, thresholds: higher(0.85, 0.7), hint: "Fewer than 30 engaged sessions" }),
        ],
      };
      h.sections.carbon = { restricted: false, measures: [measure({ id: "gramsCo2", label: "Grams of CO2", unit: "count", value: 12, n: 5, kind: "INFORMATIONAL", band: "GREY" })] };
      // A section sent as null is skipped, not rendered.
      (h.sections as Record<string, unknown>).broken = null;
      return h;
    }
    default:
      return baseHealth(agentId, "Support");
  }
}

// ── Series and markers ────────────────────────────────────────────

export const MARKERS: HealthMarker[] = [
  { id: "m-123", at: "2026-11-03T10:12:00Z", kind: "DEPLOYMENT", label: "v8 deployed (production)", version: 8 },
  { id: "m-124", at: "2026-10-25T08:00:00Z", kind: "SNIPPET_CHANGE", label: "Snippet \"tone\" edited" },
  // A kind the UI does not know: shown by its label.
  { id: "m-125", at: "2026-10-28T08:00:00Z", kind: "QUOTA_CHANGE", label: "Quota raised" },
];

/** The last day of every mocked period (the fixtures' "today"). */
export const MOCK_TODAY = "2026-11-10";

export function days(count: number, end = MOCK_TODAY): string[] {
  const last = Date.parse(`${end}T00:00:00Z`);
  return Array.from({ length: count }, (_, i) => new Date(last - (count - 1 - i) * 86_400_000).toISOString().slice(0, 10));
}

/** Daily points; `allNull` answers a measure with no daily values yet (a point per day, every value null). */
export function seriesFor(measureId: string, base: number, unit: Series["unit"], count = 30, allNull = false): Series {
  const points: SeriesPoint[] = days(count).map((day, i) => {
    if (allNull) return { day, value: null, n: 0 };
    // One empty day, as the contract promises ("a point with value null, n 0").
    if (i === 7) return { day, value: null, n: 0 };
    const wobble = Math.sin(i / 3) * 0.1 + (i > 22 ? -0.15 : 0);
    return { day, value: Math.max(0, base * (1 + wobble)), n: 100 + i };
  });
  return { measure: measureId, group: null, unit, points };
}

/**
 * A period of `count` days ending {@link MOCK_TODAY}, and the baseline of equal length before it,
 * as the contract computes them for `period=7d|30d|90d`.
 */
export function periodFor(count: number): { period: AgentHealth["period"]; baseline: AgentHealth["baseline"] } {
  const span = days(count * 2);
  return {
    period: { from: span[count]!, to: span[span.length - 1]!, days: count },
    baseline: { from: span[0]!, to: span[count - 1]! },
  };
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

/**
 * The portfolio as the backend sends it in a phase. H3 (the default, what the backend sends now): no agent has a
 * status or a reason. H4: the scenarios' statuses and top reasons, for the badges and the status filter.
 */
export function portfolioFixture(phase: "H3" | "H4" = "H3"): Portfolio {
  const portfolio = h4Portfolio();
  if (phase === "H4") return portfolio;
  return { ...portfolio, items: portfolio.items.map((i) => ({ ...i, status: null, topReason: null })) };
}

function h4Portfolio(): Portfolio {
  return {
    items: [
      item(HEALTH_AGENTS.attention, "Order Bot", { platformIncident: true }),
      item(HEALTH_AGENTS.silence, "Quiet Resolver", { sessions: 640, sessionsDelta: 0.12 }),
      item(HEALTH_AGENTS.healthy, "Support", {}),
      item(HEALTH_AGENTS.preH4, "Pre-status Agent", { sessions: 90, sessionsDelta: null }),
      item(HEALTH_AGENTS.noData, "Brand New Agent", { sessions: 12, sessionsDelta: null, keyMeasures: [] }),
      // The period reaches past the turn-row retention: sessions cannot be counted, and the cost says why it has no value.
      item(HEALTH_AGENTS.evaluating, "Long Window", {
        sessions: null,
        sessionsDelta: null,
        hint: "Sessions cannot be counted beyond the 90-day turn retention.",
        keyMeasures: [
          measure({ id: "costPerSession", label: "Cost per session", unit: "usd", value: null, n: 0, band: "GREY", hint: "Sessions cannot be counted for this period" }),
        ],
      }),
    ],
    // Incidents are detected per day: `from` and `to` are days, not instants.
    platformIncidents: [{ from: "2026-11-08", to: "2026-11-08", failureClass: "RATE_LIMITED", agents: 12 }],
    nextCursor: null,
  };
}

export const SESSION_ROWS: HealthSessionRow[] = [
  // `sessionNo` is the step index the session starts at: 0 for a conversation's first session, the index of its first turn for a later one.
  { conversationId: "conv-aa", sessionNo: 0, startedAt: "2026-11-09T10:00:00Z", endedAt: "2026-11-09T10:05:00Z", agentVersion: 8, origin: "api", engaged: true, outcome: "ABANDONED", evidence: "inferred", provisional: false, turns: 6, costUsd: 0.031, failures: 1 },
  { conversationId: "conv-bb", sessionNo: 0, startedAt: "2026-11-09T11:00:00Z", endedAt: "2026-11-09T11:08:00Z", agentVersion: 8, origin: "v1", engaged: true, outcome: "ASSUMED_RESOLVED", evidence: "silence", provisional: false, turns: 4, costUsd: 0.02, failures: 0 },
  { conversationId: "conv-cc", sessionNo: 17, startedAt: "2026-11-09T12:00:00Z", endedAt: null, agentVersion: 8, origin: "email", engaged: true, outcome: "RESOLVED", evidence: "self_reported", provisional: true, turns: 9, costUsd: null, failures: 0 },
  { conversationId: "conv-dd", sessionNo: 3, startedAt: "2026-11-09T13:00:00Z", endedAt: "2026-11-09T13:02:00Z", agentVersion: 7, origin: "api", engaged: true, outcome: "ASSUMED_RESOLVED", evidence: "agent_closed", provisional: false, turns: 3, costUsd: 0.011, failures: 0 },
  // An origin that is not judged: no outcome, no evidence. Listed only when that origin is asked for.
  { conversationId: "conv-ee", sessionNo: 0, startedAt: "2026-11-09T14:00:00Z", endedAt: "2026-11-09T14:01:00Z", agentVersion: 8, origin: "schedule", engaged: true, outcome: null, evidence: null, provisional: false, turns: 2, costUsd: 0.004, failures: 0 },
];
