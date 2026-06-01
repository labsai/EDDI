import { api, isApiError } from "../api-client";

// ==================== Types ====================

/**
 * Matches the backend RestToolHistory.getConversationCosts() response:
 *   Map.of("conversationId", ..., "totalCost", ..., "toolCallCount", ..., "toolUsage", ...)
 * where toolUsage is Map<String, Integer> (tool name → call count).
 */
export interface ConversationCosts {
  conversationId: string;
  totalCost: number;
  toolCallCount: number;
  toolUsage: Record<string, number>;
}

/**
 * Matches the backend RestToolHistory.getRateLimit() response:
 *   Map.of("tool", toolName, "limit", info.limit, "remaining", info.remaining, "resetTimeMs", info.resetTimeMs)
 */
export interface ToolRateLimit {
  tool: string;
  limit: number;
  remaining: number;
  resetTimeMs: number;
}

/**
 * Matches the backend RestToolHistory.getCacheStats() response:
 *   Map.of("size", ..., "hits", ..., "misses", ..., "hitRate", ..., "perToolStats", ..., "details", ...)
 */
export interface CacheStats {
  size: number;
  hits: number;
  misses: number;
  hitRate: number;
  perToolStats: Record<string, { hits: number; misses: number }>;
  details: string;
}

export interface ToolHistoryEntry {
  toolName: string;
  args: Record<string, unknown>;
  result: string | null;
  durationMs: number;
  cost: number;
  timestamp: string;
}

/**
 * Matches the backend RestToolHistory.getCosts() response:
 *   Map.of("totalCost", costTracker.getTotalCost(), "summary", summary)
 */
export interface ToolCostSummary {
  totalCost: number;
  summary: string;
}

// ==================== API Functions ====================

const TOOLS_BASE = "/llm/tools";

/**
 * Get cost breakdown for a specific conversation.
 * Returns null when the backend has no cost data yet (404).
 */
export async function getConversationCosts(
  conversationId: string,
): Promise<ConversationCosts | null> {
  try {
    return await api.get<ConversationCosts>(
      `${TOOLS_BASE}/costs/conversation/${conversationId}`,
    );
  } catch (err) {
    // Backend returns 404 when no cost data exists for the conversation yet
    if (isApiError(err) && err.status === 404) return null;
    throw err;
  }
}

/** Get rate limit info for a specific tool. */
export async function getToolRateLimit(
  toolName: string,
): Promise<ToolRateLimit> {
  return api.get<ToolRateLimit>(`${TOOLS_BASE}/ratelimit/${toolName}`);
}

/** Get cache hit/miss statistics. */
export async function getCacheStats(): Promise<CacheStats> {
  return api.get<CacheStats>(`${TOOLS_BASE}/cache/stats`);
}

/** Get tool execution history for a conversation. */
export async function getToolHistory(
  conversationId: string,
): Promise<ToolHistoryEntry[]> {
  return api.get<ToolHistoryEntry[]>(
    `${TOOLS_BASE}/history/${conversationId}`,
  );
}

/** Get global tool cost summary. */
export async function getToolCosts(): Promise<ToolCostSummary> {
  return api.get<ToolCostSummary>(`${TOOLS_BASE}/costs`);
}
