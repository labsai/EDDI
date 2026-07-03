import { api } from "../api-client";

// ── Enums ─────────────────────────────────────────────────────────

export type HitlVerdict = "APPROVED" | "REJECTED";

export type HitlTimeoutPolicy =
  | "WAIT_INDEFINITELY"
  | "AUTO_APPROVE"
  | "AUTO_REJECT"
  | "ABORT";

export type HitlGranularity = "PHASE" | "TASK";

export type HitlRejectionPolicy = "FAIL" | "RETRY";

// ── DTOs ──────────────────────────────────────────────────────────

/** Human decision on a paused conversation or group discussion. */
export interface HitlDecision {
  verdict: HitlVerdict;
  note?: string;
  /** Set server-side — not sent by the client. */
  decidedBy?: string;
}

/** Summary of a conversation awaiting human approval.
 *  Jackson serializes absent fields as null, so nullable fields are `| null`. */
export interface PendingApprovalSummary {
  conversationId: string;
  agentId?: string | null;
  /** Set only for group-surface pauses. */
  groupId?: string | null;
  userId?: string | null;
  pausedAt: string;
  pauseReason?: string | null;
  timeoutPolicy?: HitlTimeoutPolicy | string | null;
  /** ISO-8601 duration of the configured approval timeout. */
  approvalTimeout?: string | null;
}

/** Group discussion approval request body. */
export interface GroupApprovalRequest {
  decision: HitlDecision;
  /** taskId → "APPROVED" | "REJECTED" (for TASK granularity). */
  taskApprovals?: Record<string, string>;
}

// ── Config types ──────────────────────────────────────────────────

/** Agent-level HITL configuration. */
export interface AgentHitlConfig {
  /** ISO-8601 duration (e.g., "PT15M"), null = indefinite. */
  approvalTimeout?: string | null;
  timeoutPolicy?: HitlTimeoutPolicy;
  /** Designer-supplied reason shown to approvers in pending-approval listings
   *  and approval-status (e.g. "Deletion requires manager sign-off"). */
  pauseReason?: string | null;
}

/** Group-level HITL configuration (extends agent-level with granularity). */
export interface GroupHitlConfig {
  approvalTimeout?: string | null;
  timeoutPolicy?: HitlTimeoutPolicy;
  granularity?: HitlGranularity;
  onTaskRejection?: HitlRejectionPolicy;
}

// ── SSE payload types (group surface) ─────────────────────────────

export interface HitlPauseEvent {
  phaseIndex: number;
  phaseName: string;
  reason: string;
  granularity: string;
}

export interface HitlResumeEvent {
  verdict: string;
  note?: string;
  decidedBy?: string;
}

export interface CancelledEvent {
  reason?: string;
  cancelledBy?: string;
}

// ── Regular conversation HITL API ─────────────────────────────────

/** Submit a human decision to resume a paused conversation.
 *  POST /agents/{conversationId}/resume */
export function resumeConversation(
  conversationId: string,
  decision: HitlDecision,
): Promise<void> {
  return api.post(`/agents/${conversationId}/resume`, decision);
}

/** Get the approval status of a paused conversation.
 *  GET /agents/{conversationId}/approval-status */
export function getApprovalStatus(
  conversationId: string,
  detail: "summary" | "full" = "summary",
): Promise<Record<string, unknown>> {
  return api.get(`/agents/${conversationId}/approval-status?detail=${detail}`);
}

/** List conversations currently awaiting human approval.
 *  GET /agents/pending-approvals */
export function listPendingApprovals(
  limit = 200,
): Promise<PendingApprovalSummary[]> {
  return api.get<PendingApprovalSummary[]>(
    `/agents/pending-approvals?limit=${limit}`,
  );
}

/** Cancel a paused or in-progress conversation.
 *  POST /agents/{conversationId}/cancel */
export function cancelConversation(
  conversationId: string,
): Promise<void> {
  return api.post(`/agents/${conversationId}/cancel`);
}

// ── Group discussion HITL API ─────────────────────────────────────

/** Approve or reject a paused group discussion phase.
 *
 *  The manager UI resumes group discussions via the streaming variant
 *  (`streamGroupApproval` in `lib/api/groups.ts`, `POST .../approve/stream`),
 *  which submits the decision AND streams the resumed progress on one
 *  connection. This non-streaming binding is kept for programmatic callers that
 *  only need the decision submitted.
 *  POST /groups/{groupId}/conversations/{gcId}/approve */
export function approveGroupPhase(
  groupId: string,
  gcId: string,
  request: GroupApprovalRequest,
): Promise<void> {
  return api.post(
    `/groups/${groupId}/conversations/${gcId}/approve`,
    request,
  );
}

/** Get the approval status of a paused group conversation.
 *  GET /groups/{groupId}/conversations/{gcId}/approval-status */
export function getGroupApprovalStatus(
  groupId: string,
  gcId: string,
  detail: "summary" | "full" = "summary",
): Promise<Record<string, unknown>> {
  return api.get(
    `/groups/${groupId}/conversations/${gcId}/approval-status?detail=${detail}`,
  );
}

/** Cross-group HITL inbox: group conversations awaiting approval across ALL
 *  groups, in one request (backend answers this natively — no per-group fan-out).
 *  GET /groups/pending-approvals */
export function listAllGroupPendingApprovals(
  limit = 200,
): Promise<PendingApprovalSummary[]> {
  return api.get<PendingApprovalSummary[]>(
    `/groups/pending-approvals?limit=${limit}`,
  );
}

/** Cancel a group discussion.
 *  POST /groups/{groupId}/conversations/{gcId}/cancel */
export function cancelGroupDiscussion(
  groupId: string,
  gcId: string,
): Promise<void> {
  return api.post(`/groups/${groupId}/conversations/${gcId}/cancel`);
}
