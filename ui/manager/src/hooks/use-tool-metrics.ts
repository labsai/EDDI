import { useQuery } from "@tanstack/react-query";
import {
  getConversationCosts,
  getToolRateLimit,
  getCacheStats,
  getToolHistory,
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

/** Fetch rate limit for a specific tool — polls every 10s. */
export function useToolRateLimit(toolName: string | null, enabled = true) {
  return useQuery({
    queryKey: [...METRICS_KEY, "rateLimit", toolName],
    queryFn: () => getToolRateLimit(toolName!),
    enabled: !!toolName && enabled,
    refetchInterval: enabled ? 10_000 : false,
    staleTime: 9_000,
  });
}

/** Fetch cache stats — on-demand. */
export function useCacheStats(enabled = false) {
  return useQuery({
    queryKey: [...METRICS_KEY, "cacheStats"],
    queryFn: getCacheStats,
    enabled,
    staleTime: 30_000,
  });
}

/** Fetch tool history for a conversation — on-demand. */
export function useToolHistory(conversationId: string | null, enabled = false) {
  return useQuery({
    queryKey: [...METRICS_KEY, "history", conversationId],
    queryFn: () => getToolHistory(conversationId!),
    enabled: !!conversationId && enabled,
    staleTime: 10_000,
  });
}
