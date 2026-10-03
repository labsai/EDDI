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
