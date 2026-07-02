import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listPendingApprovals,
  resumeConversation,
  cancelConversation,
  approveGroupPhase,
  cancelGroupDiscussion,
  listGroupPendingApprovals,
  type HitlDecision,
  type GroupApprovalRequest,
} from "@/lib/api/hitl";

// ── Queries ──────────────────────────────────────────────────────

/** Pending approvals for regular conversations. */
export function usePendingApprovals(limit = 200) {
  return useQuery({
    queryKey: ["pending-approvals", { limit }],
    queryFn: () => listPendingApprovals(limit),
    refetchInterval: 10_000,
  });
}

/** Pending approvals for a specific group's discussions. */
export function useGroupPendingApprovals(groupId: string | undefined, limit = 100) {
  return useQuery({
    queryKey: ["group-pending-approvals", groupId, { limit }],
    queryFn: () => listGroupPendingApprovals(groupId!, limit),
    enabled: !!groupId,
    refetchInterval: 10_000,
  });
}

// ── Mutations ────────────────────────────────────────────────────

/** Resume a paused 1:1 conversation with an APPROVED/REJECTED decision. */
export function useResumeConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ conversationId, decision }: { conversationId: string; decision: HitlDecision }) =>
      resumeConversation(conversationId, decision),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.invalidateQueries({ queryKey: ["pending-approvals"] });
    },
  });
}

/** Cancel a paused or in-progress 1:1 conversation. */
export function useCancelConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (conversationId: string) => cancelConversation(conversationId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["conversations"] });
      qc.invalidateQueries({ queryKey: ["pending-approvals"] });
    },
  });
}

/** Approve or reject a paused group discussion phase. */
export function useApproveGroupPhase() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, gcId, request }: { groupId: string; gcId: string; request: GroupApprovalRequest }) =>
      approveGroupPhase(groupId, gcId, request),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["group-conversations"] });
      qc.invalidateQueries({ queryKey: ["group-pending-approvals"] });
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
      qc.invalidateQueries({ queryKey: ["group-conversations"] });
      qc.invalidateQueries({ queryKey: ["group-pending-approvals"] });
    },
  });
}
