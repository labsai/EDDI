import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { alertKeys } from "@/lib/query-keys";
import { isApiError } from "@/lib/api-client";
import {
  acknowledgeAlertEvent,
  acknowledgeAlertEvents,
  createAlertRule,
  deleteAlertRule,
  getUnacknowledgedCount,
  listAlertEvents,
  listAlertRules,
  retryAlertEvent,
  testAlertRule,
  updateAlertRule,
  type AlertEventQuery,
  type AlertRule,
} from "@/lib/api/alerts";

/** How often the unacknowledged count refreshes — the Feedback badge's cadence. */
export const ALERT_POLL_MS = 30_000;

/** The backend's error code of a failed call, if it sent one. */
export function alertErrorCode(error: unknown): string | undefined {
  return isApiError(error) ? (error as { code?: string }).code : undefined;
}

export function useAlertEvents(query: AlertEventQuery, index: number, limit: number, enabled = true) {
  return useQuery({
    queryKey: alertKeys.events(query, index, limit),
    queryFn: () => listAlertEvents(query, index, limit),
    enabled,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

/** Unacknowledged alert events — the sidebar badge. */
export function useUnacknowledgedAlerts(agentId?: string, enabled = true) {
  return useQuery({
    queryKey: alertKeys.unacknowledged(agentId),
    queryFn: () => getUnacknowledgedCount(agentId),
    enabled,
    refetchInterval: ALERT_POLL_MS,
    retry: false,
  });
}

export function useAlertRules(agentId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: alertKeys.rules(agentId),
    queryFn: () => listAlertRules(agentId),
    enabled,
    retry: false,
  });
}

function useAlertMutation<TArgs, TResult>(fn: (args: TArgs) => Promise<TResult>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => queryClient.invalidateQueries({ queryKey: alertKeys.all }),
  });
}

export function useAcknowledgeAlert() {
  return useAlertMutation((id: string) => acknowledgeAlertEvent(id));
}

export function useAcknowledgeAlerts() {
  return useAlertMutation(({ ids, query }: { ids: string[] | null; query?: AlertEventQuery }) => acknowledgeAlertEvents(ids, query));
}

export function useRetryAlert() {
  return useAlertMutation((id: string) => retryAlertEvent(id));
}

export function useSaveAlertRule() {
  return useAlertMutation((rule: AlertRule) => (rule.id ? updateAlertRule(rule.id, rule) : createAlertRule(rule)));
}

export function useDeleteAlertRule() {
  return useAlertMutation((id: string) => deleteAlertRule(id));
}

export function useTestAlertRule() {
  return useMutation({ mutationFn: (rule: AlertRule) => testAlertRule(rule) });
}
