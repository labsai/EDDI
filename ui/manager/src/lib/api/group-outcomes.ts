import { api } from "../api-client";
import { seg } from "./path-segment";

// ─────────────────────────────────────────────────────────────────
// Team outcomes: what came of the work. An external system reports a reply,
// a merged PR or a metric reading against a backlog task or an approval
// decision; the team's RETRO phase and approval analytics read it back.
// Off until the group's configuration carries an `outcomeConfig`.
// ─────────────────────────────────────────────────────────────────

export type OutcomeSubjectType = "TASK" | "DECISION";

/** Backend `OutcomeEvent`. */
export interface OutcomeEvent {
  id: string;
  groupId: string;
  subjectType: OutcomeSubjectType;
  subjectId: string;
  /** What happened, e.g. `reply_received`, `pr_merged`, `metric_reading`. */
  kind: string;
  metric: string | null;
  value: number | null;
  unit: string | null;
  note: string | null;
  /** The reporting system, as the sender declared it. */
  source: string | null;
  /** The authenticated principal that posted it. */
  recordedBy: string | null;
  occurredAt: string;
  recordedAt: string;
  idempotencyKey: string | null;
}

/** Body of `recordGroupOutcome` (backend `IRestGroupOutcomes.OutcomeRequest`). */
export interface RecordOutcomeRequest {
  subjectType: OutcomeSubjectType;
  subjectId: string;
  kind: string;
  metric?: string;
  value?: number;
  unit?: string;
  /** At most 1000 characters. */
  note?: string;
  source?: string;
  occurredAt?: string;
  idempotencyKey?: string;
}

/** Backend `GroupOutcomeConfig`: the group's `outcomeConfig`. */
export interface OutcomeConfig {
  maxPerSubject: number;
  maxStored: number;
  retentionDays: number;
  /** Events a RETRO phase quotes; `0` keeps outcomes out of the retro. */
  retroDigestSize: number;
}

/**
 * A team's outcomes, newest first: one subject's when `subjectId` is given,
 * otherwise the most recent. An empty list for a group with outcomes off.
 * GET /groupstore/groups/{groupId}/outcomes
 */
export function listGroupOutcomes(groupId: string, subjectId?: string, limit?: number): Promise<OutcomeEvent[]> {
  const p = new URLSearchParams();
  if (subjectId) p.set("subjectId", subjectId);
  if (limit) p.set("limit", String(limit));
  const query = p.toString();
  return api.get<OutcomeEvent[]>(`/groupstore/groups/${seg(groupId)}/outcomes${query ? `?${query}` : ""}`);
}

/**
 * Record an outcome. 201 when stored, 200 when the `idempotencyKey` was already
 * stored. Failures: 400 (invalid), 404 (no such task), 409 (outcomes not enabled
 * for the group, or the subject is full).
 * POST /groupstore/groups/{groupId}/outcomes
 */
export function recordGroupOutcome(groupId: string, request: RecordOutcomeRequest): Promise<OutcomeEvent> {
  return api.post<OutcomeEvent>(`/groupstore/groups/${seg(groupId)}/outcomes`, request);
}

/** One approval category's line of `GET /hitl/analytics/outcomes`. */
export interface CategoryOutcomes {
  category: string;
  decisions: number;
  decisionsWithOutcomes: number;
  outcomes: number;
  byKind: Record<string, number>;
}

/** `GET /hitl/analytics/outcomes` (backend `OutcomeAnalyticsService.Result`). */
export interface OutcomeAnalytics {
  groupId: string;
  decisions: number;
  outcomes: number;
  truncated: boolean;
  categories: CategoryOutcomes[];
}
