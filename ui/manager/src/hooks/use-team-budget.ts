import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getTeamBudget,
  getTeamBudgetHistory,
  listTeamBudgets,
  setCurrentMonthCap,
  transferTeamBudget,
  type TransferRequest,
} from "@/lib/api/team-budget";

const KEY = ["team-budget"] as const;

/** One team's budget this month. `retry: false`: a 403 or 404 is an answer, not a blip. */
export function useTeamBudget(groupId: string | undefined) {
  return useQuery({
    queryKey: [...KEY, "one", groupId],
    queryFn: () => getTeamBudget(groupId!),
    enabled: !!groupId,
    retry: false,
    refetchInterval: 60_000,
  });
}

export function useTeamBudgetHistory(groupId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, "history", groupId],
    queryFn: () => getTeamBudgetHistory(groupId!),
    enabled: !!groupId && enabled,
    retry: false,
  });
}

export function useTeamBudgets(groupIds: readonly string[]) {
  return useQuery({
    queryKey: [...KEY, "list", groupIds],
    queryFn: () => listTeamBudgets(groupIds),
    enabled: groupIds.length > 0,
    retry: false,
  });
}

export function useSetCurrentMonthCap() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { groupId: string; capUsd: number; reason: string }) => setCurrentMonthCap(v.groupId, v.capUsd, v.reason),
    onSuccess: () => void qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function useTransferTeamBudget() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { request: TransferRequest; idempotencyKey: string }) => transferTeamBudget(v.request, v.idempotencyKey),
    onSuccess: () => void qc.invalidateQueries({ queryKey: KEY }),
  });
}
