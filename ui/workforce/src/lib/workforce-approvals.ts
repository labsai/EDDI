import { parseIsoDuration } from "@eddi/core";
import type { PendingApprovalSummary } from "@eddi/app-kit/lib/api/hitl";
import { chatHref } from "@/lib/workforce-sessions";

/**
 * One thing waiting on a human, as Workforce's approvals indicator lists it
 * (T2.3). Built from the pending-approvals summaries only — no extra request.
 */
export interface ApprovalItem {
  conversationId: string;
  surface: "regular" | "group";
  /** A HUMAN member's turn in a team session, rather than an approval gate. */
  humanTurn: boolean;
  /** The signed-in person's own conversation (they wait on it, or decide it as its owner). */
  mine: boolean;
  agentId: string | null;
  groupId: string | null;
  /** Epoch ms, or NaN when the server sent nothing readable. */
  pausedAt: number;
  /** When the timeout policy decides on its own (epoch ms), or null when it waits indefinitely. */
  deadline: number | null;
  timeoutPolicy: string | null;
  /** Where the item opens: in Workforce for one's own, the Manager's inbox for a review. */
  href: string;
}

function epochOf(value: string | null | undefined): number {
  if (!value) return Number.NaN;
  const numeric = Number(value);
  return Number.isFinite(numeric) && value.trim() !== "" ? numeric : Date.parse(value);
}

function deadlineOf(summary: PendingApprovalSummary, pausedAt: number): number | null {
  if (!summary.timeoutPolicy || summary.timeoutPolicy === "WAIT_INDEFINITELY") return null;
  const timeout = parseIsoDuration(summary.approvalTimeout ?? "");
  return timeout === null || !Number.isFinite(pausedAt) ? null : pausedAt + timeout;
}

/** Where a team session opens — the same link as the sidebar's (`teamSession`). */
export function teamSessionHref(groupId: string, conversationId: string): string {
  return `/workforce/${encodeURIComponent(groupId)}?conversation=${encodeURIComponent(conversationId)}`;
}

/** The Manager's approvals inbox, where an approver decides (Workforce links to it). */
export const MANAGER_APPROVALS_HREF = "/manage/approvals";

function hrefOf(summary: PendingApprovalSummary, mine: boolean): string {
  if (!mine) return MANAGER_APPROVALS_HREF;
  if (summary.groupId) return teamSessionHref(summary.groupId, summary.conversationId);
  if (summary.agentId) return chatHref(summary.agentId, summary.conversationId);
  // An own pause with no agent recorded cannot be opened here; the inbox can.
  return MANAGER_APPROVALS_HREF;
}

/**
 * Both pending-approvals lists as one, for `username` (`null`: auth is off, or
 * the token names nobody — everything counts as one's own, as in the sidebar).
 *
 * - Deduplicated by conversation id, as the Manager's inbox and badge do.
 * - "Mine" follows the sidebar's rule (`use-workforce-sessions.ts`): no owner
 *   recorded, or the owner is the signed-in user. Everything else reached us
 *   only because the caller is an approver or administrator.
 * - Ordered by what decides itself soonest, then by the oldest pause.
 */
export function approvalItems(
  regular: readonly PendingApprovalSummary[],
  group: readonly PendingApprovalSummary[],
  username: string | null,
): ApprovalItem[] {
  const seen = new Set<string>();
  const items: ApprovalItem[] = [];
  for (const summary of [...regular, ...group]) {
    if (!summary?.conversationId || seen.has(summary.conversationId)) continue;
    seen.add(summary.conversationId);
    const mine = username === null || !summary.userId || summary.userId === username;
    const pausedAt = epochOf(summary.pausedAt);
    items.push({
      conversationId: summary.conversationId,
      surface: summary.groupId ? "group" : "regular",
      humanTurn: summary.pauseType === "HUMAN_TURN",
      mine,
      agentId: summary.agentId ?? null,
      groupId: summary.groupId ?? null,
      pausedAt,
      deadline: deadlineOf(summary, pausedAt),
      timeoutPolicy: summary.timeoutPolicy ?? null,
      href: hrefOf(summary, mine),
    });
  }
  return items.sort((a, b) => {
    const da = a.deadline ?? Number.POSITIVE_INFINITY;
    const db = b.deadline ?? Number.POSITIVE_INFINITY;
    if (da !== db) return da - db;
    return (Number.isFinite(a.pausedAt) ? a.pausedAt : 0) - (Number.isFinite(b.pausedAt) ? b.pausedAt : 0);
  });
}
