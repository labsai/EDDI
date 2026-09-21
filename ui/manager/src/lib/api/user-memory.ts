import { api } from "../api-client";

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
  return api.get<UserMemoryEntry[]>(`${BASE}/${encodeURIComponent(userId)}`);
}

export async function searchMemories(
  userId: string,
  query: string,
): Promise<UserMemoryEntry[]> {
  return api.get<UserMemoryEntry[]>(
    `${BASE}/${encodeURIComponent(userId)}/search?q=${encodeURIComponent(query)}`,
  );
}

export async function getMemoriesByCategory(
  userId: string,
  category: string,
): Promise<UserMemoryEntry[]> {
  return api.get<UserMemoryEntry[]>(
    `${BASE}/${encodeURIComponent(userId)}/category/${encodeURIComponent(category)}`,
  );
}

export async function getMemoryByKey(
  userId: string,
  key: string,
): Promise<UserMemoryEntry | null> {
  return api.get<UserMemoryEntry | null>(
    `${BASE}/${encodeURIComponent(userId)}/key/${encodeURIComponent(key)}`,
  );
}

export async function upsertMemory(
  entry: UserMemoryEntry,
): Promise<void> {
  return api.put(BASE, entry);
}

export async function deleteMemory(entryId: string): Promise<void> {
  return api.delete(`${BASE}/entry/${encodeURIComponent(entryId)}`);
}

export async function deleteAllForUser(userId: string): Promise<void> {
  return api.delete(`${BASE}/${encodeURIComponent(userId)}`);
}

export async function countMemories(
  userId: string,
): Promise<number> {
  const res = await api.get<{ count: number }>(
    `${BASE}/${encodeURIComponent(userId)}/count`,
  );
  return typeof res === "number" ? res : (res as { count: number }).count ?? 0;
}
