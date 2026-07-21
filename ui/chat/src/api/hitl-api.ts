/* ──────────────────────────────────────────────
   EDDI Chat — Human-in-the-Loop
   A paused conversation has no push channel on the 1:1 REST/SSE surface, so
   the client polls approval-status to learn that the pause resolved — including
   when it auto-resolves by timeout policy.

   This widget deliberately does NOT submit decisions. Deciding belongs to a
   reviewer (Manager UI, via /agents/pending-approvals); an end user approving
   their own gate defeats the oversight the pause exists to provide.
   ────────────────────────────────────────────── */

import { encodeSegment, request, requestJson } from "./http";
import type { ConversationState } from "@/types";

export interface PendingToolCall {
  callId: string;
  toolName: string;
  source: string;
  /** Redacted and length-capped — the raw arguments are never exposed. */
  arguments: string;
  argsTruncated: boolean;
  gateReason: string;
}

export interface ToolCallPauseDetails {
  type: "TOOL_CALL";
  calls: PendingToolCall[];
  executedUngatedCalls: string[];
  outcomeUnknown: string[];
}

export interface RulePauseDetails {
  type: "RULE";
  reason: string | null;
  actions: string[];
}

export type PauseDetails = ToolCallPauseDetails | RulePauseDetails;

/** The `detail=summary` projection of GET /agents/{id}/approval-status. */
export interface ApprovalStatus {
  conversationId: string;
  state: ConversationState;
  /** ISO-8601 instant, or "" when not paused. */
  pausedAt: string;
  pauseReason: string;
  /** AUTO_APPROVE | AUTO_REJECT | ABORT | WAIT_INDEFINITELY, or "". */
  timeoutPolicy: string;
  /** ISO-8601 duration, e.g. "PT15M", or "". */
  approvalTimeout: string;
  pauseDetails: PauseDetails | null;
}

const ISO_DURATION = /^P(?:\d+D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/;

/** Parse an ISO-8601 duration's time components into milliseconds. */
export function parseIsoDuration(value: string): number | null {
  if (!value) return null;
  const m = ISO_DURATION.exec(value.trim());
  if (!m) return null;
  const [, h, min, s] = m;
  if (!h && !min && !s) return null;
  return (
    (Number(h ?? 0) * 3600 + Number(min ?? 0) * 60 + Number(s ?? 0)) * 1000
  );
}

/**
 * Epoch millis at which the pause auto-decides, or null when it never does.
 *
 * `pausedAt` is `Instant.toString()` on the summary endpoint (ISO-8601), but
 * the `detail=full` snapshot serializes instants as epoch millis, so both
 * forms are accepted.
 */
export function approvalDeadline(status: ApprovalStatus): number | null {
  const timeout = parseIsoDuration(status.approvalTimeout);
  if (timeout === null || !status.pausedAt) return null;

  const numeric = Number(status.pausedAt);
  const pausedAt = Number.isFinite(numeric) && status.pausedAt.trim() !== ""
    ? numeric
    : Date.parse(status.pausedAt);
  if (!Number.isFinite(pausedAt)) return null;

  return pausedAt + timeout;
}

/** Names of the tools awaiting approval; empty unless this is a TOOL_CALL pause. */
export function gatedToolNames(status: ApprovalStatus): string[] {
  const details = status.pauseDetails;
  if (!details || details.type !== "TOOL_CALL") return [];
  return (details.calls ?? []).map((c) => c.toolName).filter(Boolean);
}

/** One line explaining why the turn stalled. */
export function pauseHeadline(status: ApprovalStatus): string {
  const tools = gatedToolNames(status);
  if (tools.length === 1) {
    return `A reviewer must approve "${tools[0]}" before I can continue.`;
  }
  if (tools.length > 1) {
    return `A reviewer must approve these actions before I can continue: ${tools.join(", ")}.`;
  }

  const details = status.pauseDetails;
  const reason =
    (details && details.type === "RULE" ? details.reason : null) ||
    status.pauseReason;

  return reason
    ? `A reviewer must approve this step before I can continue — ${reason}.`
    : "A reviewer must approve this step before I can continue.";
}

const POLL_BASE_MS = 3_000;
const POLL_MAX_MS = 30_000;

/**
 * Delay before the next approval-status poll, given how many polls have
 * already happened. Starts responsive so a quick approval feels immediate,
 * then backs off — approvals routinely take minutes, and a fixed fast interval
 * would hammer the endpoint for the whole wait. Capped so a resolved pause is
 * never missed for more than half a minute.
 */
export function nextPollDelay(attempt: number): number {
  const n = Math.max(0, attempt);
  return Math.min(POLL_BASE_MS * 2 ** Math.min(n, 10), POLL_MAX_MS);
}

/** Read the approval status of a conversation. */
export async function getApprovalStatus(
  conversationId: string,
): Promise<ApprovalStatus | null> {
  return requestJson<ApprovalStatus>(
    `/agents/${encodeSegment(conversationId)}/approval-status?detail=summary`,
    undefined,
    "Failed to read approval status",
  );
}

/**
 * Cancel a running or paused turn. This is the widget's escape hatch out of a
 * pause (AWAITING_HUMAN → EXECUTION_INTERRUPTED) and its stop-generating
 * control. Throws ApiError with status 409 when there is nothing to cancel.
 */
export async function cancelConversation(
  conversationId: string,
): Promise<void> {
  await request(
    `/agents/${encodeSegment(conversationId)}/cancel`,
    { method: "POST" },
    "Failed to cancel",
  );
}
