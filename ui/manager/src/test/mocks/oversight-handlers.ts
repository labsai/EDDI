import { http, HttpResponse } from "msw";
import type { ApprovalSummary, AuditSample, CategoryView } from "@/lib/api/hitl-oversight";

/**
 * HITL oversight — `/hitl/analytics`, `/hitl/samples` and `/hitl/autonomy`
 * against an in-memory store shaped like the backend's records
 * (ApprovalSummary, AuditSample, AutonomyService.CategoryView).
 *
 * A PATCH of the sample {@link REVIEWED_SAMPLE_ID} answers 409
 * `SAMPLE_ALREADY_REVIEWED`, the way a sample reviewed meanwhile does; an apply
 * with an `expectedAgentVersion` other than the recommendation's answers 409
 * `AGENT_CHANGED`.
 */

export const REVIEWED_SAMPLE_ID = "sample-reviewed";
export const NOTES_CATEGORY = "tool:http.post:/crm/notes";
export const LOOKUP_CATEGORY = "crm.lookups";

const hour = 3_600_000;

function sample(id: string, partial: Partial<AuditSample> = {}): AuditSample {
  const sampled = new Date(Date.now() - 2 * hour).toISOString();
  return {
    id,
    agentId: "agent1",
    agentVersion: 3,
    conversationId: "conv-1",
    callId: `call-${id}`,
    toolName: "createNote",
    source: "http",
    endpoint: "post:/crm/notes",
    category: NOTES_CATEGORY,
    rulePattern: "http.post:/crm/notes",
    exemptPattern: "http.post:/crm/notes",
    argumentsRedacted: '{"contact":"c-17","text":"Called back about the invoice"}',
    argsDigest: "a".repeat(64),
    resultRedacted: null,
    sampledAt: sampled,
    reviewDueAt: new Date(Date.now() + 70 * hour).toISOString(),
    status: "PENDING",
    reasonCode: null,
    note: null,
    reviewedBy: null,
    reviewedAt: null,
    version: 1,
    ...partial,
  };
}

function summary(key: string, partial: Partial<ApprovalSummary> = {}): ApprovalSummary {
  return {
    key,
    total: 40,
    byOutcome: { APPROVED: 34, APPROVED_EDITED: 2, REJECTED: 2, AUTO_APPROVED: 2 },
    humanDecisions: 38,
    uneditedApprovalRate: 0.8947,
    editRate: 0.0526,
    rejectionRate: 0.0526,
    topReasonCodes: [{ code: "TONE", count: 2 }],
    latencyP50Ms: 180_000,
    latencyP90Ms: 2_700_000,
    latencyHistogram: [0, 0, 2, 6, 20, 8, 2, 2, 0, 0, 0],
    timeoutShare: 0.05,
    channelMix: { MANAGER: 30, SLACK: 8, TIMEOUT: 2 },
    legacy: 0,
    samples: { sampled: 6, pending: 2, reviewed: 4, flagged: 1, expired: 0, expiredShare: 0, flaggedReasonCodes: [{ code: "TONE", count: 1 }] },
    ...partial,
  };
}

function initialSamples(): AuditSample[] {
  return [sample("sample-1"), sample(REVIEWED_SAMPLE_ID)];
}

function initialAutonomy(): CategoryView[] {
  const evidence = { n: 31, approved: 30, edited: 1, rejected: 0, uneditedRate: 0.9677, windowFrom: new Date(Date.now() - 30 * 24 * hour).toISOString(), windowTo: new Date().toISOString() };
  return [
    {
      agentId: "agent1",
      category: NOTES_CATEGORY,
      tier: "READY",
      pattern: "http.post:/crm/notes",
      evidence,
      evidenceStatus: "eligible",
      state: {
        agentId: "agent1",
        category: NOTES_CATEGORY,
        evidenceResetAt: null,
        lastDemotionAt: null,
        version: 2,
        suspension: null,
        recommendation: {
          id: "rec-1",
          kind: "PROMOTE",
          status: "OPEN",
          computedAt: new Date().toISOString(),
          basedOnAgentVersion: 3,
          evidence,
          pattern: "http.post:/crm/notes",
          applicable: true,
          proposedHitlConfig: { toolApprovals: { requireApproval: ["http.post:*"], exempt: ["http.post:/crm/notes"] } },
          changes: ["Add 'http.post:/crm/notes' to hitlConfig.toolApprovals.exempt", "Sample 10% of 'http.post:/crm/notes' calls for review"],
          leavesNoGate: false,
        },
      },
    },
    {
      agentId: "agent1",
      category: LOOKUP_CATEGORY,
      tier: "SUSPENDED",
      pattern: "http.get:/crm/*",
      evidence: null,
      evidenceStatus: null,
      state: {
        agentId: "agent1",
        category: LOOKUP_CATEGORY,
        evidenceResetAt: new Date().toISOString(),
        lastDemotionAt: new Date().toISOString(),
        version: 4,
        recommendation: null,
        suspension: {
          active: true,
          pattern: "http.get:/crm/*",
          since: new Date().toISOString(),
          trigger: "REASON_CODE",
          triggerRef: "sample-9",
          reasonCode: "FALSE_CLAIM",
          suspendedBy: "system:audit-sample",
          liftedBy: null,
        },
      },
    },
  ];
}

let samples = initialSamples();
let autonomy = initialAutonomy();

/** Restores the fixtures (tests that mutate call it in `beforeEach`). */
export function resetOversightMocks() {
  samples = initialSamples();
  autonomy = initialAutonomy();
}

function error(status: number, code: string, message: string) {
  return HttpResponse.json({ error: code, message }, { status });
}

function find(request: Request, agentId: string | undefined): CategoryView | undefined {
  const category = new URL(request.url).searchParams.get("category");
  return autonomy.find((v) => v.agentId === agentId && v.category === category);
}

export const oversightHandlers = [
  http.get("*/hitl/analytics/summary", ({ request }) => {
    const groupBy = new URL(request.url).searchParams.get("groupBy") ?? "none";
    if (groupBy === "category") {
      return HttpResponse.json({
        groupBy,
        truncated: false,
        buckets: [summary(LOOKUP_CATEGORY, { total: 0, humanDecisions: 0, uneditedApprovalRate: null }), summary(NOTES_CATEGORY)],
      });
    }
    return HttpResponse.json({ groupBy, truncated: false, buckets: [summary(groupBy === "none" ? "all" : "x")] });
  }),

  http.get("*/hitl/samples/pending-count", () => HttpResponse.json({ pending: samples.filter((s) => s.status === "PENDING").length })),

  http.get("*/hitl/samples/:id", ({ params }) => {
    const found = samples.find((s) => s.id === params.id);
    if (!found) return error(404, "SAMPLE_NOT_FOUND", "Sample not found");
    return HttpResponse.json({
      sample: found,
      reasonCodes: [
        { code: "FALSE_CLAIM", label: "States something untrue" },
        { code: "TONE", label: "Wrong tone or style" },
        { code: "OTHER", label: "Other" },
      ],
    });
  }),

  http.patch("*/hitl/samples/:id", async ({ params, request }) => {
    const body = (await request.json()) as { verdict: string; reasonCode?: string; note?: string; expectedVersion: number };
    const found = samples.find((s) => s.id === params.id);
    if (!found) return error(404, "SAMPLE_NOT_FOUND", "Sample not found");
    if (params.id === REVIEWED_SAMPLE_ID || found.status !== "PENDING" || body.expectedVersion !== found.version) {
      return error(409, "SAMPLE_ALREADY_REVIEWED", "The sample was reviewed meanwhile");
    }
    if (body.verdict === "FLAGGED" && !body.reasonCode) return error(400, "UNKNOWN_REASON_CODE", "A flagged sample needs a reasonCode");
    const reviewed: AuditSample = {
      ...found,
      status: body.verdict === "FLAGGED" ? "FLAGGED" : "OK",
      reasonCode: body.verdict === "FLAGGED" ? (body.reasonCode ?? null) : null,
      note: body.note ?? null,
      reviewedBy: "admin",
      reviewedAt: new Date().toISOString(),
      version: found.version + 1,
    };
    samples = samples.map((s) => (s.id === found.id ? reviewed : s));
    return HttpResponse.json(reviewed);
  }),

  http.get("*/hitl/samples", ({ request }) => {
    const url = new URL(request.url);
    const status = url.searchParams.get("status") ?? "PENDING";
    const index = Number(url.searchParams.get("index") ?? 0);
    const limit = Number(url.searchParams.get("limit") ?? 20);
    const rows = samples.filter((s) => status === "ALL" || s.status === status);
    return HttpResponse.json({ total: rows.length, index, limit, rows: rows.slice(index, index + limit) });
  }),

  http.get("*/hitl/autonomy", () => HttpResponse.json({ categories: autonomy })),

  http.post("*/hitl/autonomy/:agentId/apply", async ({ params, request }) => {
    const view = find(request, params.agentId as string);
    const body = (await request.json()) as { recommendationId: string; expectedAgentVersion: number };
    const recommendation = view?.state.recommendation;
    if (!view || !recommendation || recommendation.status !== "OPEN" || recommendation.id !== body.recommendationId) {
      return error(409, "RECOMMENDATION_CHANGED", "This recommendation is no longer open");
    }
    if (body.expectedAgentVersion !== recommendation.basedOnAgentVersion) return error(409, "AGENT_CHANGED", "The agent changed");
    const applied: CategoryView = {
      ...view,
      tier: "PROMOTED",
      state: { ...view.state, recommendation: { ...recommendation, status: "APPLIED", appliedBy: "admin", resultingAgentVersion: body.expectedAgentVersion + 1 } },
    };
    autonomy = autonomy.map((v) => (v === view ? applied : v));
    return HttpResponse.json(applied);
  }),

  http.post("*/hitl/autonomy/:agentId/dismiss", async ({ params, request }) => {
    const view = find(request, params.agentId as string);
    const body = (await request.json()) as { recommendationId: string; note: string };
    if (!body.note?.trim()) return error(400, "NOTE_REQUIRED", "A note is required");
    if (!view?.state.recommendation) return error(409, "RECOMMENDATION_CHANGED", "This recommendation is no longer open");
    const dismissed: CategoryView = {
      ...view,
      tier: "GATED",
      state: { ...view.state, recommendation: { ...view.state.recommendation, status: "DISMISSED" } },
    };
    autonomy = autonomy.map((v) => (v === view ? dismissed : v));
    return HttpResponse.json(dismissed);
  }),

  http.post("*/hitl/autonomy/:agentId/lift", async ({ params, request }) => {
    const view = find(request, params.agentId as string);
    const body = (await request.json()) as { note: string };
    if (!body.note?.trim()) return error(400, "NOTE_REQUIRED", "A note is required");
    if (!view?.state.suspension?.active) return error(409, "NOT_SUSPENDED", "The category is not suspended");
    const lifted: CategoryView = {
      ...view,
      tier: "GATED",
      state: { ...view.state, suspension: { ...view.state.suspension, active: false, liftedBy: "admin" } },
    };
    autonomy = autonomy.map((v) => (v === view ? lifted : v));
    return HttpResponse.json(lifted);
  }),

  http.post("*/hitl/autonomy/:agentId/suspend", async ({ request }) => {
    const body = (await request.json()) as { note: string };
    if (!body.note?.trim()) return error(400, "NOTE_REQUIRED", "A note is required");
    return error(400, "NOT_A_TOOL_CATEGORY", "Only a tool category can be suspended");
  }),
];
