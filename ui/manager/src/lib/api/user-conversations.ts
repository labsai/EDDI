import { api } from "../api-client";

// ==================== Types ====================

export interface UserConversation {
  intent: string;
  userId: string;
  environment: string;
  agentId: string;
  conversationId: string;
}

// ==================== API Functions ====================

const BASE = "/userconversationstore/userconversations";

/**
 * Get the active user conversation for a given intent+userId pair.
 * GET /userconversationstore/userconversations/{intent}/{userId}
 */
export async function getUserConversation(
  intent: string,
  userId: string,
): Promise<UserConversation> {
  return api.get<UserConversation>(
    `${BASE}/${encodeURIComponent(intent)}/${encodeURIComponent(userId)}`,
  );
}

/**
 * Create/update a user conversation binding.
 * POST /userconversationstore/userconversations/{intent}/{userId}
 */
export async function createUserConversation(
  intent: string,
  userId: string,
  data: UserConversation,
): Promise<{ location: string }> {
  return api.post<{ location: string }>(
    `${BASE}/${encodeURIComponent(intent)}/${encodeURIComponent(userId)}`,
    data,
  );
}

/**
 * Delete a user conversation binding.
 * DELETE /userconversationstore/userconversations/{intent}/{userId}
 */
export async function deleteUserConversation(
  intent: string,
  userId: string,
): Promise<void> {
  return api.delete(
    `${BASE}/${encodeURIComponent(intent)}/${encodeURIComponent(userId)}`,
  );
}
