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

/** Summary of a conversation awaiting human approval. */
export interface PendingApprovalSummary {
  conversationId: string;
  agentId?: string;
  /** Set only for group-surface pauses. */
  groupId?: string;
  userId?: string;
  pausedAt: string;
  pauseReason?: string;
  timeoutPolicy?: string;
  /** ISO-8601 duration of the configured approval timeout. */
  approvalTimeout?: string;
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

/** List this group's conversations currently awaiting human approval.
 *  GET /groups/{groupId}/conversations/pending-approvals */
export function listGroupPendingApprovals(
  groupId: string,
  limit = 100,
): Promise<PendingApprovalSummary[]> {
  return api.get<PendingApprovalSummary[]>(
    `/groups/${groupId}/conversations/pending-approvals?limit=${limit}`,
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
