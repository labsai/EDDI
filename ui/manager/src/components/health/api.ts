import { api, ApiClientError, seg } from "./deps";

/**
 * Agent Health — the REST contract in planning/agent-health-api.md, section by
 * section. Field names are the contract's; do not add fields here that it lacks
 * (put the request in that file's "UI requests" section instead).
 */

export type HealthPeriod = "7d" | "30d" | "90d";
export const HEALTH_PERIODS: readonly HealthPeriod[] = ["7d", "30d", "90d"];

export type Band = "GREEN" | "AMBER" | "RED" | "GREY";
export type MeasureKind = "SCORED" | "INFORMATIONAL" | "CHECK";
export type HealthStatus = "HEALTHY" | "WATCH" | "NEEDS_ATTENTION" | "NOT_ENOUGH_DATA";
export type MeasureUnit = "ratio" | "usd" | "ms" | "count" | "tokens" | "score";

/** The origins of H0. Unknown ids render generically. */
export const HEALTH_ORIGINS = [
  "api", "v1", "mcp", "email", "slack", "webhook", "schedule", "a2a", "group-member", "delegated", "agent-test",
] as const;

export const SECTION_IDS = ["reliability", "effectiveness", "quality", "efficiency", "adoption", "business"] as const;
export type SectionId = (typeof SECTION_IDS)[number];

export interface DateRange { from: string; to: string }
export interface Interval { low: number; high: number }
export interface BreakdownEntry { key: string; label: string; value: number | null; n: number }
export interface Thresholds { green: number; red: number; higherIsBetter: boolean }

export interface Measure {
  id: string;
  label: string;
  kind: MeasureKind;
  critical?: boolean;
  unit: MeasureUnit;
  value: number | null;
  n: number;
  interval?: Interval | null;
  baseline?: number | null;
  delta?: number | null;
  significant?: boolean;
  band: Band;
  thresholds?: Thresholds | null;
  breakdown?: BreakdownEntry[] | null;
  hint?: string | null;
}

export interface Reason {
  measureId: string;
  severity: Band;
  text: string;
  markerId?: string | null;
}

export interface HealthSection {
  restricted: boolean;
  /** Effectiveness only: the share of engaged sessions with an outcome. */
  coverage?: number | null;
  measures: Measure[];
}

export interface HealthCheck {
  id: string;
  label: string;
  state: "PASS" | "FAIL" | "UNKNOWN";
  at: string | null;
  stale: boolean;
  version?: number | null;
}

export interface AgentHealth {
  agentId: string;
  agentName: string;
  period: DateRange & { days: number };
  baseline: DateRange;
  version: number | null;
  origins: string[];
  status: HealthStatus | null;
  reasons: Reason[];
  preset: string;
  generatedAt: string;
  dataThrough: string;
  sections: Partial<Record<SectionId, HealthSection>> & Record<string, HealthSection | undefined>;
  checks: HealthCheck[];
}

export interface SeriesPoint { day: string; value: number | null; n: number }
export interface Series { measure: string; group: string | null; unit: MeasureUnit; points: SeriesPoint[] }
export type MarkerKind =
  | "DEPLOYMENT" | "VERSION_FIRST_TURN" | "SNIPPET_CHANGE" | "GLOBAL_VARIABLE_CHANGE"
  | "MODEL_PRICE_CHANGE" | "PROFILE_CHANGE" | "NOTE";
export interface HealthMarker { id: string; at: string; kind: MarkerKind | string; label: string; version?: number | null }
export interface HealthSeriesResponse {
  agentId: string;
  period: DateRange & { days: number };
  series: Series[];
  markers: HealthMarker[];
}

export interface CompareSide {
  version: number;
  window: DateRange;
  sections: AgentHealth["sections"];
}
export interface CompareDelta {
  measureId: string;
  from: number | null;
  to: number | null;
  delta: number | null;
  significant: boolean;
  better: boolean | null;
}
export interface HealthCompare { agentId: string; from: CompareSide; to: CompareSide; deltas: CompareDelta[] }

export interface PortfolioItem {
  agentId: string;
  agentName: string;
  status: HealthStatus | null;
  preset: string;
  topReason: Reason | null;
  sessions: number;
  sessionsDelta: number | null;
  keyMeasures: Measure[];
  platformIncident: boolean;
}
export interface PlatformIncident { from: string; to: string; failureClass: string; agents: number }
export interface Portfolio { items: PortfolioItem[]; platformIncidents: PlatformIncident[]; nextCursor: string | null }
export type PortfolioSort = "attention" | "name" | "traffic";

export interface ProfileOverride { measure: string; state: string; reason: string; until: string }
export interface HealthProfile {
  agentId?: string;
  stored?: boolean;
  preset: string;
  critical: string[];
  thresholds: Record<string, { green?: number; red?: number }>;
  budgets: Record<string, number>;
  slo: { agentTurnSuccess?: number; apdexTMs?: number; minTurnsForBurnAlert?: number };
  overrides: ProfileOverride[];
}
/** Fills the optional parts of a stored profile so an editor never reads `undefined`. */
export function normalizeProfile(p: Partial<HealthProfile> & { preset: string }): HealthProfile {
  return {
    ...p,
    preset: p.preset,
    critical: p.critical ?? [],
    thresholds: p.thresholds ?? {},
    budgets: p.budgets ?? {},
    slo: p.slo ?? {},
    overrides: p.overrides ?? [],
  };
}

export interface ProfileViolation { path: string; message: string }
export interface HealthPreset {
  id: string;
  label: string;
  critical: string[];
  thresholds: Record<string, { green?: number; red?: number }>;
}

export type SessionOutcome =
  | "RESOLVED" | "ASSUMED_RESOLVED" | "ESCALATED" | "ABANDONED" | "FAILED" | "DEFLECTED" | "ENDED_QUIETLY" | "OPEN";
export const SESSION_OUTCOMES: readonly SessionOutcome[] = [
  "RESOLVED", "ASSUMED_RESOLVED", "ESCALATED", "ABANDONED", "FAILED", "DEFLECTED", "ENDED_QUIETLY", "OPEN",
];
export type EvidenceSource =
  | "external_event" | "conversation_id_event" | "agent_action" | "agent_closed" | "silence"
  | "self_reported" | "structural" | "inferred" | "judged";
export const EVIDENCE_SOURCES: readonly EvidenceSource[] = [
  "external_event", "conversation_id_event", "agent_action", "agent_closed", "silence",
  "self_reported", "structural", "inferred", "judged",
];
export interface HealthSessionRow {
  conversationId: string;
  sessionNo: number;
  startedAt: string;
  endedAt: string | null;
  agentVersion: number | null;
  origin: string;
  engaged: boolean;
  outcome: SessionOutcome | string;
  evidence: EvidenceSource | string;
  provisional: boolean;
  turns: number;
  costUsd: number | null;
  failures: number;
}
export interface HealthSessionsPage { items: HealthSessionRow[]; nextCursor: string | null }

export interface OutcomeSuggestion {
  action: string;
  suggested: SessionOutcome | string;
  sessionsEndingWith: number;
  reason: string;
}

export interface ReportLink {
  id: string;
  /** Present on the create answer only; the token is never listed again. */
  url?: string;
  expiresAt: string;
  sections: string[];
  createdAt?: string;
  createdBy?: string;
}

// ── Query building ────────────────────────────────────────────────

export interface HealthQuery {
  period?: HealthPeriod;
  version?: number | null;
  origins?: readonly string[];
}

function qs(params: Array<[string, string | number | null | undefined]>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of params) if (v !== undefined && v !== null && v !== "") sp.append(k, String(v));
  const text = sp.toString();
  return text ? `?${text}` : "";
}

function baseParams(q: HealthQuery): Array<[string, string | number | null | undefined]> {
  return [["period", q.period], ["version", q.version], ...(q.origins ?? []).map((o): [string, string] => ["origin", o])];
}

const health = (agentId: string) => `/agents/${seg(agentId)}/health`;

export const healthPaths = {
  health: (agentId: string, q: HealthQuery = {}) => `${health(agentId)}${qs(baseParams(q))}`,
  series: (agentId: string, measures: readonly string[], q: HealthQuery = {}, groupBy?: "none" | "version" | "origin") =>
    `${health(agentId)}/series${qs([...measures.map((m): [string, string] => ["measure", m]), ...baseParams(q), ["groupBy", groupBy]])}`,
  compare: (agentId: string, from: number, to: number, days?: number) =>
    `${health(agentId)}/compare${qs([["from", from], ["to", to], ["days", days]])}`,
  portfolio: (o: { period?: HealthPeriod; sort?: PortfolioSort; status?: readonly string[]; limit?: number; cursor?: string } = {}) =>
    `/health/agents${qs([["period", o.period], ["sort", o.sort], ...(o.status ?? []).map((s): [string, string] => ["status", s]), ["limit", o.limit], ["cursor", o.cursor]])}`,
  profile: (agentId: string) => `${health(agentId)}/profile`,
  presets: () => "/health/presets",
  sessions: (agentId: string, q: HealthQuery & { outcome?: readonly string[]; evidence?: readonly string[]; limit?: number; cursor?: string }) =>
    `${health(agentId)}/sessions${qs([
      ...baseParams(q),
      ...(q.outcome ?? []).map((o): [string, string] => ["outcome", o]),
      ...(q.evidence ?? []).map((e): [string, string] => ["evidence", e]),
      ["limit", q.limit], ["cursor", q.cursor],
    ])}`,
  suggestions: (agentId: string) => `${health(agentId)}/outcome-suggestions`,
  export: (agentId: string, o: { format: "json" | "csv"; period?: HealthPeriod; kind?: "daily" | "sessions" }) =>
    `${health(agentId)}/export${qs([["format", o.format], ["period", o.period], ["kind", o.kind]])}`,
  reports: (agentId: string) => `${health(agentId)}/reports`,
  report: (agentId: string, id: string) => `${health(agentId)}/reports/${seg(id)}`,
};

// ── Calls ─────────────────────────────────────────────────────────

export const getAgentHealth = (agentId: string, q: HealthQuery = {}) => api.get<AgentHealth>(healthPaths.health(agentId, q));
export const getHealthSeries = (agentId: string, measures: readonly string[], q: HealthQuery = {}, groupBy?: "none" | "version" | "origin") =>
  api.get<HealthSeriesResponse>(healthPaths.series(agentId, measures, q, groupBy));
export const getHealthCompare = (agentId: string, from: number, to: number, days?: number) =>
  api.get<HealthCompare>(healthPaths.compare(agentId, from, to, days));
export const getPortfolio = (o: Parameters<typeof healthPaths.portfolio>[0] = {}) => api.get<Portfolio>(healthPaths.portfolio(o));
export const getHealthProfile = (agentId: string) => api.get<HealthProfile>(healthPaths.profile(agentId));
export const putHealthProfile = (agentId: string, profile: HealthProfile) => api.put<HealthProfile>(healthPaths.profile(agentId), profile);
export const deleteHealthProfile = (agentId: string) => api.delete<void>(healthPaths.profile(agentId));
export const getHealthPresets = () => api.get<HealthPreset[]>(healthPaths.presets());
export const getHealthSessions = (agentId: string, q: Parameters<typeof healthPaths.sessions>[1]) =>
  api.get<HealthSessionsPage>(healthPaths.sessions(agentId, q));
export const getOutcomeSuggestions = (agentId: string) =>
  api.get<{ items: OutcomeSuggestion[] }>(healthPaths.suggestions(agentId));
export const createReportLink = (agentId: string, body: { expiresInDays: number; sections: string[] }) =>
  api.post<ReportLink>(healthPaths.reports(agentId), body);
export const listReportLinks = (agentId: string) => api.get<ReportLink[] | { items: ReportLink[] }>(healthPaths.reports(agentId)).then(
  (r) => (Array.isArray(r) ? r : r.items ?? []),
);
export const revokeReportLink = (agentId: string, id: string) => api.delete<void>(healthPaths.report(agentId, id));

/**
 * Download an export through the API client's own request path (token renewal
 * before sending, one retry on a 401, errors built by `apiErrorFromResponse`).
 * The export carries the period only; version and origin filters do not apply.
 */
export async function downloadHealthExport(
  agentId: string,
  o: { format: "json" | "csv"; period?: HealthPeriod; kind?: "daily" | "sessions" },
): Promise<void> {
  const res = await api.getRaw(healthPaths.export(agentId, o), {
    Accept: o.format === "csv" ? "text/csv" : "application/json",
  });
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `agent-health-${agentId}-${o.period ?? "30d"}-${o.kind ?? "daily"}.${o.format}`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // Revoked later: some browsers start the download after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

// ── Error helpers ─────────────────────────────────────────────────

export type DrillDownDenial = "REVIEW_DISABLED" | "FORBIDDEN";

/** Why the drill-down refused: the contract's `{ reason }` body on a 403. */
export function drillDownDenial(error: unknown): DrillDownDenial | null {
  if (!(error instanceof ApiClientError) || error.status !== 403) return null;
  const reason = (error.body as { reason?: unknown } | undefined)?.reason;
  return reason === "REVIEW_DISABLED" ? "REVIEW_DISABLED" : "FORBIDDEN";
}

/** Per-path violations of a refused profile save (400). Empty when there are none. */
export function profileViolations(error: unknown): ProfileViolation[] {
  if (!(error instanceof ApiClientError) || error.status !== 400) return [];
  const list = (error.body as { violations?: unknown } | undefined)?.violations;
  if (!Array.isArray(list)) return [];
  return list.filter(
    (v): v is ProfileViolation =>
      typeof v === "object" && v !== null && typeof (v as ProfileViolation).path === "string" && typeof (v as ProfileViolation).message === "string",
  );
}
