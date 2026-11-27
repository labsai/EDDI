import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  approveSpendControlChange,
  getPendingSpendControlChanges,
  rejectSpendControlChange,
} from "@eddi/app-kit/lib/api/spend-control";

const spendControlKeys = {
  pending: ["spend-control", "pending"] as const,
};

/** Pending spend-control changes; off for non-admins (the endpoint is admin-only). */
export function usePendingSpendControlChanges(enabled: boolean) {
  return useQuery({
    queryKey: spendControlKeys.pending,
    queryFn: getPendingSpendControlChanges,
    enabled,
    refetchInterval: 30_000,
  });
}

/**
 * After a decision the catalog or a quota may have changed: refresh both, and the list.
 * Returns the refetches so the mutation's onSettled waits for them: the decision stays
 * pending (dialog open, buttons busy) until the pages hold the approved document. A
 * Quotas form edited in the gap would otherwise go dirty on the pre-approval quota,
 * stop syncing, and save it back over the approved one (the quota PUT has no version).
 */
function useInvalidateAfterDecision() {
  const qc = useQueryClient();
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: ["spend-control"] }),
      qc.invalidateQueries({ queryKey: ["model-prices"] }),
      qc.invalidateQueries({ queryKey: ["quotas"] }),
    ]);
}

export function useApproveSpendControlChange() {
  const invalidate = useInvalidateAfterDecision();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string }) => approveSpendControlChange(id, note),
    onSettled: invalidate,
  });
}

export function useRejectSpendControlChange() {
  const invalidate = useInvalidateAfterDecision();
  return useMutation({
    mutationFn: ({ id, note }: { id: string; note?: string }) => rejectSpendControlChange(id, note),
    onSettled: invalidate,
  });
}
