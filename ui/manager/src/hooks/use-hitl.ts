import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listPendingApprovals,
  listAllGroupPendingApprovals,
  getApprovalStatus,
  resumeConversation,
  cancelConversation,
  cancelGroupDiscussion,
  type HitlDecision,
} from "@/lib/api/hitl";
import { useChatStore } from "@/hooks/use-chat";

/** Clear a stale pause banner in the (persistent, app-wide) chat store when the
 *  conversation it belongs to is resumed/cancelled from elsewhere (e.g. the
 *  conversation-detail page or the approvals inbox) — the chat panel/drawer
 *  doesn't reload on its own and would otherwise keep showing "awaiting
 *  approval" for an already-resolved conversation. */
function clearChatPauseIfCurrent(conversationId: string) {
  const chatState = useChatStore.getState();
  if (chatState.conversationId === conversationId) {
    chatState.setPaused(false, null);
  }
}

// ── Queries ──────────────────────────────────────────────────────

/** Pending approvals for regular (1:1) conversations. */
export function usePendingApprovals(limit = 200) {
  return useQuery({
    queryKey: ["pending-approvals", { limit }],
    queryFn: () => listPendingApprovals(limit),
    refetchInterval: 10_000,
  });
}

/**
 * Cross-group HITL inbox — every group's pending approvals in ONE request via
 * the backend's `GET /groups/pending-approvals` (no per-group fan-out). The
 * backend returns a plain, `limit`-capped list with no truncation flag of its
 * own; `truncated` is a client-side heuristic (result count reached `limit`)
 * and can false-positive when the true count is exactly `limit`.
 */
export function useAllGroupPendingApprovals(limit = 200) {
  const query = useQuery({
    queryKey: ["all-group-pending-approvals", { limit }],
    queryFn: () => listAllGroupPendingApprovals(limit),
    refetchInterval: 10_000,
  });
  return {
    data: query.data,
    isLoading: query.isLoading,
    isError: query.isError,
    truncated: (query.data?.length ?? 0) >= limit,
  };
}

/**
 * Structured approval status of a paused 1:1 conversation — the only source of
 * `pauseDetails` (per-call tool name + redacted arguments for a TOOL_CALL pause,
 * or the rule reason/actions for a RULE pause). Enable only while the
 * conversation is actually AWAITING_HUMAN.
 */
export function useApprovalStatus(conversationId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ["approval-status", conversationId],
    queryFn: () => getApprovalStatus(conversationId!),
    enabled: enabled && !!conversationId,
  });
}

// ── Mutations ────────────────────────────────────────────────────

/** Resume a paused 1:1 conversation with an APPROVED/REJECTED decision. */
export function useResumeConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ conversationId, decision }: { conversationId: string; decision: HitlDecision }) =>
      resumeConversation(conversationId, decision),
    onSuccess: (_data, { conversationId }) => {
      qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.invalidateQueries({ queryKey: ["pending-approvals"] });
      qc.invalidateQueries({ queryKey: ["approval-status", conversationId] });
      clearChatPauseIfCurrent(conversationId);
    },
  });
}

/** Cancel a paused or in-progress 1:1 conversation. */
export function useCancelConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (conversationId: string) => cancelConversation(conversationId),
    onSuccess: (_data, conversationId) => {
      qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.invalidateQueries({ queryKey: ["pending-approvals"] });
      qc.invalidateQueries({ queryKey: ["approval-status", conversationId] });
      clearChatPauseIfCurrent(conversationId);
    },
  });
}

/** Cancel a group discussion. */
export function useCancelGroupDiscussion() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, gcId }: { groupId: string; gcId: string }) =>
      cancelGroupDiscussion(groupId, gcId),
    onSuccess: () => {
      // Real list key is camelCase ["groupConversations", …] (see use-groups.ts).
      qc.invalidateQueries({ queryKey: ["groupConversations"] });
      qc.invalidateQueries({ queryKey: ["all-group-pending-approvals"] });
    },
  });
}
