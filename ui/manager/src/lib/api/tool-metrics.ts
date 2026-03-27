import { api } from "../api-client";

// ==================== Types ====================

export interface ConversationCosts {
  conversationId: string;
  totalCost: number;
  totalToolCalls: number;
  toolUsage: Record<string, { calls: number; totalCost: number }>;
}

export interface ToolRateLimit {
  toolName: string;
  limit: number;
  remaining: number;
  resetAt: string; // ISO instant
}

export interface CacheStats {
  totalHits: number;
  totalMisses: number;
  hitRate: number;
  perToolStats: Record<string, { hits: number; misses: number }>;
}

export interface ToolHistoryEntry {
  toolName: string;
  args: Record<string, unknown>;
  result: string | null;
  durationMs: number;
  cost: number;
  timestamp: string;
}

export interface ToolCostSummary {
  totalCost: number;
  totalCalls: number;
  perTool: Record<string, { calls: number; cost: number }>;
}

// ==================== API Functions ====================

const TOOLS_BASE = "/llm/tools";

/** Get cost breakdown for a specific conversation. */
export async function getConversationCosts(
  conversationId: string,
): Promise<ConversationCosts> {
  return api.get<ConversationCosts>(
    `${TOOLS_BASE}/costs/conversation/${conversationId}`,
  );
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
