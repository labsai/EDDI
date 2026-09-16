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

/** Loop-protection policy for a tool pause that re-pauses with no progress. */
export type HitlOnNoProgress = "WAIT_FOR_HUMAN" | "AUTO_REJECT" | "ABORT";

/** Behavior when a member agent's tool call is gated inside a group turn.
 *  Only REJECT is accepted in v1; INBOX is reserved (backend rejects it 400). */
export type HitlInGroupTurns = "REJECT";

/**
 * Distinguishes a behavior-rule pause from a gated-tool-call pause, or (group
 * surface only) a HUMAN group member's turn (I6) — rides the SAME
 * `PendingApprovalSummary`/pending-approvals endpoints as RULE/TOOL_CALL,
 * discriminated by this field, rather than a dedicated "my pending turns"
 * endpoint. A HUMAN_TURN pause is "you're up", not "approve/reject" — resume it
 * via `groups.ts`'s `submitHumanInput`, never the HITL approve endpoints.
 */
export type HitlPauseType = "RULE" | "TOOL_CALL" | "HUMAN_TURN";

/** Tool source qualifiers recognized by the backend pattern engine
 *  (ToolApprovalPatterns.KNOWN_SOURCES). */
export const TOOL_SOURCES = [
  "builtin",
  "http",
  "mcp",
  "a2a",
  "dynamic",
  "memory",
  "recall",
] as const;
export type ToolSource = (typeof TOOL_SOURCES)[number];

// ── Field limits (mirror the backend save-time validators) ────────

/** Top-level reviewer note cap — HitlDecision.MAX_NOTE_LENGTH. */
export const MAX_NOTE_LENGTH = 4096;
/** Per-call reviewer note cap — ToolCallDecision.MAX_NOTE_LENGTH. */
export const MAX_TOOL_CALL_NOTE_LENGTH = 1024;
/** pauseReason / pendingMessage cap — HitlConfigValidation.MAX_PAUSE_REASON_LENGTH. */
export const MAX_PAUSE_REASON_LENGTH = 500;
/** amendedArguments byte cap — PendingToolCallBatch.AMENDED_ARGS_MAX_BYTES. */
export const AMENDED_ARGS_MAX_BYTES = 32_768;

// ── DTOs ──────────────────────────────────────────────────────────

/** Per-tool-call verdict inside a TOOL_CALL resume body. Calls not listed in
 *  `HitlDecision.toolDecisions` inherit the top-level verdict. `amendedArguments`
 *  is only valid on an APPROVED call, must be a JSON-object string, and cannot
 *  amend a call whose arguments were truncated at pause time. */
export interface ToolCallDecision {
  verdict: HitlVerdict;
  note?: string;
  amendedArguments?: string;
}

/** Human decision on a paused conversation or group discussion. */
export interface HitlDecision {
  verdict: HitlVerdict;
  note?: string;
  /** Per-callId verdicts for a TOOL_CALL pause (regular surface only).
   *  Omit for RULE pauses and group approvals. */
  toolDecisions?: Record<string, ToolCallDecision>;
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
  /** "RULE" (or null/legacy) = behavior-rule pause; "TOOL_CALL" = gated tool
   *  pause; "HUMAN_TURN" (group surface only, I6) = a HUMAN member's turn is up. */
  pauseType?: HitlPauseType | string | null;
  /** Names only (no arguments) of the gated tool calls — badges tool-call pauses
   *  in the inbox without a second round trip. */
  toolNames?: string[] | null;
  /** Set only when `pauseType === "HUMAN_TURN"` (I6) — the member whose turn is pending. */
  pendingMemberId?: string | null;
}

/** Group discussion approval request body. */
export interface GroupApprovalRequest {
  decision: HitlDecision;
  /** taskId → "APPROVED" | "REJECTED" (for TASK granularity). */
  taskApprovals?: Record<string, string>;
}

// ── approval-status / pauseDetails types ──────────────────────────

/**
 * The redacted HTTP request a gated call actually resolves to — backend-verified
 * at gate time (`IApiCallExecutor#resolve`) and, when `PendingToolCallView.requestPinned`
 * is true, re-derived and compared immediately before execution so what runs is
 * what was shown here. Credentials are already redacted; nothing on this object
 * is ever sensitive.
 *
 * Absent (`PendingToolCallView.requestPreview` is null/undefined) for every
 * non-http tool source, and for an http call whose config could not be resolved
 * without side effects — a client must read absence as "nothing to preview",
 * never as "this call is less real".
 */
export interface ResolvedRequestPreview {
  method: string;
  uri: string;
  queryParams: Record<string, string>;
  /** Shown even though mostly uninteresting: the fingerprint covers them too,
   *  so "approve what you are shown" has to include the whole of what is checked. */
  headers: Record<string, string>;
  body?: string | null;
  /** True when `body` was cut for display — never affects the fingerprint,
   *  which is computed over the full body before any capping. */
  bodyTruncated: boolean;
}

/** One gated tool call in a TOOL_CALL pause. `arguments` is ALWAYS the redacted,
 *  size-capped value — the raw arguments are never sent to a client. */
export interface PendingToolCallView {
  callId: string;
  toolName: string;
  /** builtin | http | mcp | a2a | dynamic | memory | recall | unknown. */
  source: string;
  /** Redacted, capped argument JSON (may itself be truncated). */
  arguments?: string | null;
  /** When true, the call cannot be executed on resume and cannot be amended. */
  argsTruncated: boolean;
  /** The requireApproval pattern that gated this call, e.g. "mcp:*". */
  gateReason?: string | null;
  /**
   * True when `requestPreview` is backed by a fingerprint that will be
   * re-checked immediately before execution — false for every non-http tool
   * AND for an http call previewed only best-effort (one with pre-request
   * property instructions, whose actual request can still change before it
   * runs). Independent of whether `requestPreview` itself is present: a
   * best-effort preview can exist while this is false.
   */
  requestPinned: boolean;
  /** The redacted resolved request, when determinable ahead of execution. */
  requestPreview?: ResolvedRequestPreview | null;
}

/** pauseDetails for a gated-tool-call pause. */
export interface ToolCallPauseDetails {
  type: "TOOL_CALL";
  calls: PendingToolCallView[];
  /** Ungated calls in the same batch that already executed (side effects). */
  executedUngatedCalls: string[];
  /** callIds whose prior approval crashed mid-execution — outcome unknown. */
  outcomeUnknown: string[];
}

/** pauseDetails for a behavior-rule pause (also legacy null-pauseType snapshots). */
export interface RulePauseDetails {
  type: "RULE";
  reason?: string | null;
  actions?: string[];
}

export type PauseDetails = ToolCallPauseDetails | RulePauseDetails;

/** Response of `GET /agents/{id}/approval-status?detail=summary`. Bookmark
 *  fields are `""` once the conversation is no longer paused; `pauseDetails` is
 *  `null` when not paused. */
export interface ApprovalStatusSummary {
  conversationId: string;
  /** ConversationState name, e.g. "AWAITING_HUMAN". */
  state: string;
  pausedAt?: string;
  pauseReason?: string;
  timeoutPolicy?: string;
  approvalTimeout?: string;
  pauseDetails?: PauseDetails | null;
}

// ── Config types ──────────────────────────────────────────────────

/**
 * Config-driven tool-approval gating (tool-level HITL / surface 3). Mirrors the
 * backend `ToolApprovalsConfig`. Used in two homes: agent-level default
 * (`AgentHitlConfig.toolApprovals`) and per-LLM-task override
 * (`LlmTask.toolApprovals` — a FULL REPLACE of the agent block, no merge).
 * Every field is optional; an absent or empty `requireApproval` list disables
 * the gate entirely (backward compatible).
 */
export interface ToolApprovalsConfig {
  /** Glob patterns of tools requiring approval, e.g. "mcp:*", "delete_*". */
  requireApproval?: string[] | null;
  /** Exemptions — always beat requireApproval. */
  exempt?: string[] | null;
  /** Max tool pauses per turn (default 3, valid 1..10). Fail-closed at the cap. */
  maxPausesPerTurn?: number | null;
  /** Max consecutive system (timeout) auto-approvals per turn (default 2, 0..10). */
  maxAutoApprovalsPerTurn?: number | null;
  /** Loop-protection policy on identical-fingerprint re-pause (default WAIT_FOR_HUMAN). */
  onNoProgress?: HitlOnNoProgress | null;
  /** Tool-pause timeout override (ISO-8601 duration). */
  approvalTimeout?: string | null;
  /** Tool-pause timeout policy override. An INHERITED agent-level AUTO_APPROVE is
   *  demoted to WAIT_INDEFINITELY for tool pauses — set this explicitly to
   *  auto-approve tool pauses on timeout. */
  timeoutPolicy?: HitlTimeoutPolicy | null;
  /** Approver-facing reason; the literal "{toolNames}" is substituted. ≤500 chars. */
  pauseReason?: string | null;
  /** End-user-facing chat message stored as public output at pause commit;
   *  "{toolNames}" substituted. ≤500 chars. */
  pendingMessage?: string | null;
  /** Behavior inside group turns — REJECT only in v1 (INBOX reserved). */
  inGroupTurns?: HitlInGroupTurns | null;
  /**
   * Per-tool friction, most-specific-pattern-first. Mirrors the backend
   * `ToolApprovalsConfig.rules` (EDDI PR "per-endpoint approval friction").
   * A rule tunes HOW a gated call is reviewed — it never decides WHETHER one is
   * gated; that stays in `requireApproval`/`exempt` above.
   */
  rules?: ApprovalRule[] | null;
}

/**
 * One per-tool override in `ToolApprovalsConfig.rules`. Every field but `match`
 * falls back individually to the enclosing `ToolApprovalsConfig` scalar.
 */
export interface ApprovalRule {
  /** Pattern selecting the calls this rule applies to. Same language as
   *  `requireApproval` — bare name, "source:name", or "source.method:path". */
  match: string;
  timeoutPolicy?: HitlTimeoutPolicy | null;
  approvalTimeout?: string | null;
  pauseReason?: string | null;
  pendingMessage?: string | null;
}

/** Agent-level HITL configuration. */
export interface AgentHitlConfig {
  /** ISO-8601 duration (e.g., "PT15M"), null = indefinite. */
  approvalTimeout?: string | null;
  timeoutPolicy?: HitlTimeoutPolicy;
  /** Designer-supplied reason shown to approvers in pending-approval listings
   *  and approval-status (e.g. "Deletion requires manager sign-off"). */
  pauseReason?: string | null;
  /** Tool-level approval gating applied to every LLM task in the agent (surface 3). */
  toolApprovals?: ToolApprovalsConfig | null;
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

/** Get the approval status of a paused conversation (summary view, incl.
 *  structured `pauseDetails` for RULE and TOOL_CALL pauses).
 *  GET /agents/{conversationId}/approval-status */
export function getApprovalStatus(
  conversationId: string,
): Promise<ApprovalStatusSummary> {
  return api.get<ApprovalStatusSummary>(
    `/agents/${conversationId}/approval-status?detail=summary`,
  );
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
