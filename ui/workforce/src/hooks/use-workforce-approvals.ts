import { useQuery } from "@tanstack/react-query";
import { listAllGroupPendingApprovals, listPendingApprovals, type PendingApprovalSummary } from "@eddi/app-kit/lib/api/hitl";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import { useEventDrivenInterval } from "@eddi/app-kit/hooks/use-user-events";
import { approvalItems, type ApprovalItem } from "@/lib/workforce-approvals";

/**
 * The same limit, and so the same query keys, as the Manager's inbox and
 * badge (`PENDING_APPROVALS_LIMIT` in `@eddi/app-kit/hooks/use-hitl`): an
 * approval decided anywhere in this tab invalidates one cache entry. Imported
 * by value would pull the HITL hooks (and the chat store) into Workforce's
 * first load for one number.
 */
const LIMIT = 200;

/** Polled only while the user event stream is down; the stream makes it instant. */
const FALLBACK_POLL_MS = 30_000;

/**
 * What is waiting on a human, for the person signed in (T2.3): their own paused
 * turns and team sessions, and — for an approver or administrator, whom the
 * backend answers with everyone's — the pauses they may decide.
 *
 * Both lists come from the existing pending-approvals endpoints, which every
 * role may call and which scope the answer on the server. The user event
 * stream (`approval.pending`, `approval.decided`, `team.*`) marks them stale
 * through `UserEventBridge`; they are polled only while it is not open.
 */
export function useWorkforceApprovals(): { items: ApprovalItem[]; mine: ApprovalItem[]; toReview: ApprovalItem[] } {
  const { user, method } = useAuth();
  const refetchInterval = useEventDrivenInterval(FALLBACK_POLL_MS);
  const regular = useQuery<PendingApprovalSummary[]>({
    queryKey: ["pending-approvals", { limit: LIMIT }],
    queryFn: () => listPendingApprovals(LIMIT),
    refetchInterval,
  });
  const group = useQuery<PendingApprovalSummary[]>({
    queryKey: ["all-group-pending-approvals", { limit: LIMIT }],
    queryFn: () => listAllGroupPendingApprovals(LIMIT),
    refetchInterval,
  });
  // With auth off there is one anonymous user, and everything is theirs.
  const username = method === "none" ? null : (user?.username ?? null);
  const items = approvalItems(regular.data ?? [], group.data ?? [], username);
  return {
    items,
    mine: items.filter((item) => item.mine),
    toReview: items.filter((item) => !item.mine),
  };
}
