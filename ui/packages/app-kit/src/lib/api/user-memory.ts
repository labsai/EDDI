import { api } from "../api-client";
import { seg, nameSeg } from "./path-segment";

/**
 * Persistent user memory (`/usermemorystore/memories`, docs/user-memory.md).
 *
 * Shared by the Manager's admin page (any user id) and Workforce's "What I
 * remember about you" (T2.4, the signed-in user's own). Every endpoint is open
 * to `eddi-user`, and the backend answers 403 when the user id is not the
 * caller's own principal name (`OwnershipValidator`); an admin may read anyone's.
 */

// ─── Types ───

export type MemoryVisibility = "self" | "group" | "global";

export interface UserMemoryEntry {
  id?: string;
  userId: string;
  key: string;
  value: unknown;
  category: string;
  /** `self` | `group` | `global` — a string, because old data may hold anything. */
  visibility: string;
  /** The agent that wrote it. Legacy and migrated global entries may have none. */
  sourceAgentId?: string;
  /** For `group` visibility: the teams whose agents may read it. */
  groupIds?: string[];
  sourceConversationId?: string;
  conflicted?: boolean;
  accessCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * GDPR bookkeeping rows (`IUserMemoryStore.isReservedKey`, keys starting with
 * `_gdpr_`, such as an Art. 18 processing restriction). They are not memories:
 * only the GDPR admin endpoints set or lift them, `DELETE /entry/{id}` refuses
 * them with 400 and `DELETE /{userId}` keeps them.
 */
export const RESERVED_MEMORY_KEY_PREFIX = "_gdpr_";

export function isReservedMemoryKey(key: string): boolean {
  return key.startsWith(RESERVED_MEMORY_KEY_PREFIX);
}

// ─── API Functions ───

const BASE = "/usermemorystore/memories";

export async function getAllMemories(
  userId: string,
): Promise<UserMemoryEntry[]> {
  return api.get<UserMemoryEntry[]>(`${BASE}/${nameSeg(userId)}`);
}

export async function deleteMemory(entryId: string): Promise<void> {
  return api.delete(`${BASE}/entry/${seg(entryId)}`);
}

/** Every entry of the user except the reserved GDPR rows (housekeeping, not an Art. 17 erasure). */
export async function deleteAllForUser(userId: string): Promise<void> {
  return api.delete(`${BASE}/${nameSeg(userId)}`);
}
