import { http, HttpResponse } from "msw";
import type { HealthProfile, HealthPreset, ProfileViolation, ReportLink } from "@/components/health/api";
import {
  HEALTH_AGENTS,
  MARKERS,
  SESSION_ROWS,
  compareFor,
  healthFor,
  portfolioFixture,
  seriesFor,
} from "./health-fixtures";

/**
 * Agent Health mocks (planning/agent-health-api.md). Registered in `server.ts`
 * and `browser-handlers.ts`, deliberately NOT in `handlers.ts`: the OpenAPI
 * contract test grades that file against the backend's operation snapshot, and
 * these operations do not exist there until the backend phases land. Move them
 * into `handlers.ts` (and refresh the snapshot) when they do.
 */

const PRESETS: HealthPreset[] = [
  { id: "support", label: "Support", critical: ["agentFailureRate", "resolutionRate"], thresholds: { resolutionRate: { green: 0.7, red: 0.4 } } },
  { id: "sales", label: "Sales", critical: ["agentFailureRate"], thresholds: {} },
  { id: "assistant", label: "Assistant", critical: ["agentFailureRate"], thresholds: {} },
  { id: "task", label: "Task", critical: ["agentFailureRate"], thresholds: {} },
];

/** The profile an agent has before one is stored: `stored: false` and the `support` preset values. */
const defaultProfile = (agentId: string): HealthProfile => ({
  agentId,
  stored: false,
  preset: "support",
  critical: ["agentFailureRate", "resolutionRate"],
  thresholds: { resolutionRate: { green: 0.7, red: 0.4 }, agentFailureRate: { green: 0.01, red: 0.03 } },
  budgets: { costPerResolvedUsd: 0.5 },
  slo: { agentTurnSuccess: 0.99, apdexTMs: 4000, minTurnsForBurnAlert: 50 },
  overrides: [],
});

let profiles = new Map<string, HealthProfile>();
let reports = new Map<string, Array<ReportLink>>();
let reportSeq = 0;

/** Clears the in-memory profiles and report links; call it between tests so state never leaks. */
export function resetHealthMockState() {
  profiles = new Map();
  reports = new Map();
  reportSeq = 0;
}

/** The server-side rules the profile editor must show inline. */
export function validateProfile(p: HealthProfile): ProfileViolation[] {
  const out: ProfileViolation[] = [];
  for (const [id, th] of Object.entries(p.thresholds ?? {})) {
    if (th.green !== undefined && th.red !== undefined && th.green === th.red) {
      out.push({ path: `thresholds.${id}.red`, message: "Red must differ from green." });
    }
    if (th.red !== undefined && th.red < 0) out.push({ path: `thresholds.${id}.red`, message: "Must not be negative." });
  }
  if (p.slo?.agentTurnSuccess !== undefined && (p.slo.agentTurnSuccess <= 0 || p.slo.agentTurnSuccess > 1)) {
    out.push({ path: "slo.agentTurnSuccess", message: "Must be between 0 and 1." });
  }
  (p.overrides ?? []).forEach((o, i) => {
    if (!o.reason.trim()) out.push({ path: `overrides[${i}].reason`, message: "A reason is required." });
  });
  return out;
}

const SERIES_BASE: Record<string, [number, "ratio" | "usd" | "ms" | "count" | "tokens" | "score"]> = {
  agentFailureRate: [0.01, "ratio"],
  resolutionRate: [0.6, "ratio"],
  apdex: [0.9, "score"],
  turnDurationP95: [3200, "ms"],
  costPerSession: [0.03, "usd"],
  sessions: [60, "count"],
};

/** MSW handlers for the Agent Health contract (planning/agent-health-api.md). Profiles and report links are kept in memory for the test run; the rest answer from fixtures. */
export const healthHandlers = [
  http.get("*/agents/:agentId/health/series", ({ request, params }) => {
    const url = new URL(request.url);
    const measures = url.searchParams.getAll("measure");
    const days = { "7d": 7, "30d": 30, "90d": 90 }[url.searchParams.get("period") ?? "30d"] ?? 30;
    return HttpResponse.json({
      agentId: params.agentId,
      period: { from: "2026-10-12", to: "2026-11-10", days },
      series: measures.map((m) => seriesFor(m, SERIES_BASE[m]?.[0] ?? 0.5, SERIES_BASE[m]?.[1] ?? "ratio", days)),
      markers: MARKERS,
    });
  }),

  http.get("*/agents/:agentId/health/compare", ({ request, params }) => {
    const url = new URL(request.url);
    return HttpResponse.json(compareFor(String(params.agentId), Number(url.searchParams.get("from")), Number(url.searchParams.get("to"))));
  }),

  http.get("*/agents/:agentId/health/profile", ({ params }) => {
    const id = String(params.agentId);
    return HttpResponse.json(profiles.get(id) ?? defaultProfile(id));
  }),
  http.put("*/agents/:agentId/health/profile", async ({ request, params }) => {
    const body = (await request.json()) as HealthProfile;
    const violations = validateProfile(body);
    if (violations.length > 0) return HttpResponse.json({ violations }, { status: 400 });
    const stored = { ...body, agentId: String(params.agentId), stored: true };
    profiles.set(String(params.agentId), stored);
    return HttpResponse.json(stored);
  }),
  http.delete("*/agents/:agentId/health/profile", ({ params }) => {
    profiles.delete(String(params.agentId));
    return new HttpResponse(null, { status: 204 });
  }),

  http.get("*/agents/:agentId/health/sessions", ({ request, params }) => {
    const id = String(params.agentId);
    if (id === HEALTH_AGENTS.noReview) return HttpResponse.json({ reason: "REVIEW_DISABLED" }, { status: 403 });
    if (id === HEALTH_AGENTS.forbidden) return HttpResponse.json({ reason: "FORBIDDEN" }, { status: 403 });
    const url = new URL(request.url);
    const outcomes = url.searchParams.getAll("outcome");
    const evidence = url.searchParams.getAll("evidence");
    const items = SESSION_ROWS.filter(
      (r) => (outcomes.length === 0 || outcomes.includes(r.outcome)) && (evidence.length === 0 || evidence.includes(r.evidence)),
    );
    if (id === HEALTH_AGENTS.paged) {
      // One row per page; the cursor is the index of the next row.
      const at = Number(url.searchParams.get("cursor") ?? 0);
      return HttpResponse.json({ items: items.slice(at, at + 1), nextCursor: at + 1 < items.length ? String(at + 1) : null });
    }
    return HttpResponse.json({ items, nextCursor: null });
  }),

  http.get("*/agents/:agentId/health/outcome-suggestions", () =>
    HttpResponse.json({
      items: [
        { action: "order_confirmed", suggested: "RESOLVED", sessionsEndingWith: 312, reason: "Last action of 41 % of engaged sessions" },
        { action: "handoff_human", suggested: "ESCALATED", sessionsEndingWith: 88, reason: "Last action of 12 % of engaged sessions" },
      ],
    }),
  ),

  http.get("*/agents/:agentId/health/export", ({ request }) => {
    const format = new URL(request.url).searchParams.get("format");
    return format === "csv"
      ? new HttpResponse("day,measure,value\n2026-11-10,agentFailureRate,0.004\n", { headers: { "Content-Type": "text/csv" } })
      : HttpResponse.json({ daily: [] });
  }),

  http.post("*/agents/:agentId/health/reports", async ({ request, params }) => {
    const body = (await request.json()) as { expiresInDays: number; sections: string[] };
    const id = `rep-${++reportSeq}`;
    const expiresAt = new Date(Date.now() + body.expiresInDays * 86_400_000).toISOString();
    const link: ReportLink = { id, expiresAt, sections: body.sections, createdAt: new Date().toISOString() };
    const key = String(params.agentId);
    reports.set(key, [...(reports.get(key) ?? []), link]);
    return HttpResponse.json({ ...link, url: `https://eddi.example/health-report/token-${id}` }, { status: 201 });
  }),
  http.get("*/agents/:agentId/health/reports", ({ params }) => HttpResponse.json(reports.get(String(params.agentId)) ?? [])),
  http.delete("*/agents/:agentId/health/reports/:id", ({ params }) => {
    const key = String(params.agentId);
    reports.set(key, (reports.get(key) ?? []).filter((r) => r.id !== params.id));
    return new HttpResponse(null, { status: 204 });
  }),

  // Last among the agent routes: `/agents/:agentId/health` must not swallow the sub-paths above.
  http.get("*/agents/:agentId/health", ({ params }) => {
    if (params.agentId === HEALTH_AGENTS.denied) return HttpResponse.json({ error: "FORBIDDEN", message: "No access" }, { status: 403 });
    return HttpResponse.json(healthFor(String(params.agentId)));
  }),

  http.get("*/health/agents", ({ request }) => {
    const url = new URL(request.url);
    const statuses = url.searchParams.getAll("status");
    const all = portfolioFixture();
    const matching = statuses.length ? all.items.filter((i) => i.status && statuses.includes(i.status)) : all.items;
    // Pages of three: the cursor is the index of the next item.
    const at = Number(url.searchParams.get("cursor") ?? 0);
    return HttpResponse.json({ ...all, items: matching.slice(at, at + 3), nextCursor: at + 3 < matching.length ? String(at + 3) : null });
  }),
  http.get("*/health/presets", () => HttpResponse.json(PRESETS)),
];
