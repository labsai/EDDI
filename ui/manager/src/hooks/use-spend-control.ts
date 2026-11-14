import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  approveSpendControlChange,
  getPendingSpendControlChanges,
  rejectSpendControlChange,
} from "@/lib/api/spend-control";

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

/** After a decision the catalog or a quota may have changed: refresh both, and the list. */
function useInvalidateAfterDecision() {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ["spend-control"] });
    qc.invalidateQueries({ queryKey: ["model-prices"] });
    qc.invalidateQueries({ queryKey: ["quotas"] });
  };
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
