import { api } from "../api-client";
import { seg } from "./path-segment";

/**
 * HITL oversight — approval analytics (`/hitl/analytics`), the sampled-audit
 * review queue (`/hitl/samples`) and earned autonomy (`/hitl/autonomy`). See
 * docs/hitl-oversight.md.
 *
 * Scoping is the backend's: administrators and approvers see every agent;
 * anyone else must pass `agentId` and hold EDIT on it (400
 * `AGENT_ID_REQUIRED` / 403 otherwise). Categories contain `:` and `/`, so
 * they always travel as a query parameter.
 */

export type DecisionOutcome = "APPROVED" | "APPROVED_EDITED" | "REJECTED" | "AUTO_APPROVED" | "AUTO_REJECTED" | "ABORTED";
export type SampleStatus = "PENDING" | "OK" | "FLAGGED" | "EXPIRED";
export type AutonomyTier = "GATED" | "READY" | "PROMOTED" | "SUSPENDED";
export type OversightGroupBy = "none" | "category" | "agent" | "day" | "week" | "channel" | "agentVersion";

/** Mirrors the backend's limits (AuditSample, AutonomyService). */
export const OVERSIGHT_LIMITS = {
  maxNoteLength: 2000,
  /** Above this expired share the Manager warns that review capacity is short. */
  expiredShareWarning: 0.2,
} as const;

export interface CodeCount {
  code: string;
  count: number;
}

export interface SampleStats {
  sampled: number;
  pending: number;
  reviewed: number;
  flagged: number;
  expired: number;
  expiredShare: number | null;
  flaggedReasonCodes: CodeCount[];
}

/** One bucket of `GET /hitl/analytics/summary` (`ApprovalSummary`). */
export interface ApprovalSummary {
  key: string;
  total: number;
  byOutcome: Partial<Record<DecisionOutcome, number>>;
  humanDecisions: number;
  uneditedApprovalRate: number | null;
  editRate: number | null;
  rejectionRate: number | null;
  topReasonCodes: CodeCount[];
  latencyP50Ms: number | null;
  latencyP90Ms: number | null;
  latencyHistogram: number[];
  timeoutShare: number | null;
  channelMix: Record<string, number>;
  legacy: number;
  samples: SampleStats | null;
}

export interface ApprovalSummaries {
  groupBy: string;
  truncated: boolean;
  buckets: ApprovalSummary[];
}

export interface OversightQuery {
  agentId?: string;
  category?: string;
  from?: string;
  to?: string;
}

/** One audit sample (`AuditSample`). */
export interface AuditSample {
  id: string;
  agentId: string | null;
  agentVersion: number | null;
  conversationId: string | null;
  /** Administrators only. */
  userId?: string | null;
  callId: string;
  toolName: string;
  source: string | null;
  endpoint: string | null;
  category: string;
  rulePattern: string;
  exemptPattern: string | null;
  argumentsRedacted: string;
  argsDigest: string;
  resultRedacted: string | null;
  sampledAt: string;
  reviewDueAt: string;
  status: SampleStatus;
  reasonCode: string | null;
  note: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  version: number;
}

export interface SamplePage {
  total: number;
  index: number;
  limit: number;
  rows: AuditSample[];
}

export interface ReasonCode {
  code: string;
  label: string;
}

export interface SampleDetail {
  sample: AuditSample;
  reasonCodes: ReasonCode[];
}

export interface SampleVerdict {
  verdict: "OK" | "FLAGGED";
  reasonCode?: string;
  note?: string;
  expectedVersion: number;
}

export interface Evidence {
  n: number;
  approved: number;
  edited: number;
  rejected: number;
  uneditedRate: number;
  windowFrom: string;
  windowTo: string;
}

export interface Recommendation {
  id: string;
  kind: "PROMOTE" | "REVERT";
  status: "OPEN" | "APPLIED" | "DISMISSED" | "STALE";
  computedAt: string;
  basedOnAgentVersion: number | null;
  evidence: Evidence | null;
  pattern: string | null;
  applicable: boolean;
  proposedHitlConfig: Record<string, unknown> | null;
  changes: string[];
  leavesNoGate: boolean;
  appliedBy?: string | null;
  resultingAgentVersion?: number | null;
}

export interface Suspension {
  active: boolean;
  pattern: string;
  since: string;
  trigger: "REASON_CODE" | "SAMPLE_FLAGGED" | "MANUAL";
  triggerRef: string | null;
  reasonCode: string | null;
  suspendedBy: string | null;
  liftedBy: string | null;
}

export interface AutonomyState {
  agentId: string;
  category: string;
  evidenceResetAt: string | null;
  lastDemotionAt: string | null;
  recommendation: Recommendation | null;
  suspension: Suspension | null;
  version: number;
}

/** One category as `/hitl/autonomy` shows it (`AutonomyService.CategoryView`). */
export interface CategoryView {
  agentId: string;
  category: string;
  tier: AutonomyTier;
  pattern: string | null;
  state: AutonomyState;
  evidence: Evidence | null;
  evidenceStatus: string | null;
}

function params(entries: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [key, value] of Object.entries(entries)) {
    if (value !== undefined && value !== null && value !== "") p.set(key, String(value));
  }
  const text = p.toString();
  return text ? `?${text}` : "";
}

export function summarizeApprovals(query: OversightQuery, groupBy: OversightGroupBy): Promise<ApprovalSummaries> {
  return api.get<ApprovalSummaries>(`/hitl/analytics/summary${params({ ...query, groupBy })}`);
}

export function listSamples(agentId: string | undefined, status: SampleStatus | "ALL", index: number, limit: number): Promise<SamplePage> {
  return api.get<SamplePage>(`/hitl/samples${params({ agentId, status, index, limit })}`);
}

export async function countPendingSamples(agentId?: string): Promise<number> {
  const result = await api.get<{ pending: number }>(`/hitl/samples/pending-count${params({ agentId })}`);
  return result?.pending ?? 0;
}

export function readSample(id: string): Promise<SampleDetail> {
  return api.get<SampleDetail>(`/hitl/samples/${seg(id)}`);
}

export function reviewSample(id: string, verdict: SampleVerdict): Promise<AuditSample> {
  return api.patch<AuditSample>(`/hitl/samples/${seg(id)}`, verdict);
}

export async function listAutonomy(agentId?: string): Promise<CategoryView[]> {
  const result = await api.get<{ categories: CategoryView[] }>(`/hitl/autonomy${params({ agentId })}`);
  return result?.categories ?? [];
}

function categoryPath(agentId: string, action: string, category: string): string {
  return `/hitl/autonomy/${seg(agentId)}/${action}${params({ category })}`;
}

export function applyRecommendation(agentId: string, category: string, recommendationId: string, expectedAgentVersion: number) {
  return api.post<CategoryView>(categoryPath(agentId, "apply", category), { recommendationId, expectedAgentVersion });
}

export function dismissRecommendation(agentId: string, category: string, recommendationId: string, note: string) {
  return api.post<CategoryView>(categoryPath(agentId, "dismiss", category), { recommendationId, note });
}

export function suspendCategory(agentId: string, category: string, note: string) {
  return api.post<CategoryView>(categoryPath(agentId, "suspend", category), { note });
}

export function liftSuspension(agentId: string, category: string, note: string) {
  return api.post<CategoryView>(categoryPath(agentId, "lift", category), { note });
}
