import { useCallback, useState } from "react";

const STORAGE_KEY = "workforce-recent-agents";
const MAX_ENTRIES = 200;

/** agentId → epoch ms of the last time this browser opened a chat with it. */
type RecentMap = Record<string, number>;

function readRecent(): RecentMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? (parsed as RecentMap)
      : {};
  } catch {
    return {};
  }
}

/** Record that an agent was just used. Safe to call outside React. */
export function markAgentUsed(agentId: string): void {
  if (!agentId) return;
  try {
    const next = { ...readRecent(), [agentId]: Date.now() };
    const entries = Object.entries(next);
    const kept =
      entries.length > MAX_ENTRIES
        ? Object.fromEntries(entries.sort((a, b) => b[1] - a[1]).slice(0, MAX_ENTRIES))
        : next;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(kept));
  } catch {
    // localStorage may be full or unavailable
  }
}

/**
 * Orders agents most-recently-used first (per browser); agents never used
 * keep their incoming order (already last-modified descending).
 */
export function sortByRecentUse<T extends { id: string }>(
  agents: T[],
  recent: RecentMap,
): T[] {
  return agents
    .map((agent, i) => ({ agent, i }))
    .sort((a, b) => (recent[b.agent.id] ?? 0) - (recent[a.agent.id] ?? 0) || a.i - b.i)
    .map((x) => x.agent);
}

export function useRecentAgents() {
  // Read once per mount: the dashboard remounts when the user comes back from a chat.
  const [recent] = useState<RecentMap>(readRecent);
  return useCallback(
    <T extends { id: string }>(agents: T[]) => sortByRecentUse(agents, recent),
    [recent],
  );
}
