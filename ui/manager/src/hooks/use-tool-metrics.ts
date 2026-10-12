import { useQuery } from "@tanstack/react-query";
import {
  getConversationCosts,
} from "@/lib/api/tool-metrics";

const METRICS_KEY = ["toolMetrics"] as const;

/** Fetch conversation costs — polls every 5s when enabled. */
export function useConversationCosts(
  conversationId: string | null,
  enabled = true,
) {
  return useQuery({
    queryKey: [...METRICS_KEY, "costs", conversationId],
    queryFn: () => getConversationCosts(conversationId!),
    enabled: !!conversationId && enabled,
    refetchInterval: enabled ? 5_000 : false,
    staleTime: 4_000,
  });
}
