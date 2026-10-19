import { api } from "../api-client";
import { seg, nameSeg } from "./path-segment";

// ─── Types ───

export interface UserMemoryEntry {
  id?: string;
  userId: string;
  key: string;
  value: unknown;
  category: string;
  visibility: string;
  sourceAgentId?: string;
  groupIds?: string[];
  sourceConversationId?: string;
  conflicted?: boolean;
  accessCount?: number;
  createdAt?: string;
  updatedAt?: string;
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

export async function deleteAllForUser(userId: string): Promise<void> {
  return api.delete(`${BASE}/${nameSeg(userId)}`);
}
