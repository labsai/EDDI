import { useQuery } from "@tanstack/react-query";
import { listGroupOutcomes } from "@/lib/api/group-outcomes";
import { getOutcomeAnalytics } from "@/lib/api/hitl-oversight";

export const OUTCOMES_KEY = ["group-outcomes"] as const;

/** One backlog task's outcomes. Off until `enabled` (a row is expanded), so a long backlog makes no request per row. */
export function useTaskOutcomes(groupId: string | undefined, taskId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...OUTCOMES_KEY, groupId, taskId],
    queryFn: () => listGroupOutcomes(groupId!, taskId),
    enabled: !!groupId && enabled,
  });
}

/** The team's most recent outcomes. */
export function useRecentOutcomes(groupId: string | undefined, limit = 20) {
  return useQuery({
    queryKey: [...OUTCOMES_KEY, groupId, "recent", limit],
    queryFn: () => listGroupOutcomes(groupId!, undefined, limit),
    enabled: !!groupId,
  });
}

/** Approval decisions joined with what their work reported; needs a group. */
export function useOutcomeAnalytics(groupId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: [...OUTCOMES_KEY, "analytics", groupId],
    queryFn: () => getOutcomeAnalytics(groupId!),
    enabled: enabled && !!groupId,
    retry: false,
  });
}
