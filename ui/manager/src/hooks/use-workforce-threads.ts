import { useState, useCallback } from "react";
import { useAuth } from "@/hooks/use-auth";
import { readUserScoped, storageUserId, userScopedKey } from "@/lib/user-storage";

// ─── Types ───────────────────────────────────────────────────────

interface ThreadInfo {
  memberId: string; // agentId of the advisor
  memberName: string;
  conversationId: string;
  boardId: string;
  lastActivity: number; // timestamp
}

interface UseWorkforceThreadsReturn {
  threads: ThreadInfo[];
  getThread: (boardId: string, memberId: string) => ThreadInfo | undefined;
  registerThread: (thread: Omit<ThreadInfo, "lastActivity">) => void;
  updateActivity: (boardId: string, memberId: string) => void;
  removeThread: (boardId: string, memberId: string) => void;
  getThreadsForBoard: (boardId: string) => ThreadInfo[];
}

// ─── Constants ───────────────────────────────────────────────────

const STORAGE_KEY = "workforce-threads";

// ─── Helpers ─────────────────────────────────────────────────────

function loadThreads(userId: string | undefined): ThreadInfo[] {
  try {
    const raw = readUserScoped(STORAGE_KEY, userId);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is ThreadInfo => {
      if (typeof item !== "object" || item === null) return false;
      const obj = item as Record<string, unknown>;
      return (
        typeof obj.memberId === "string" &&
        typeof obj.conversationId === "string" &&
        typeof obj.boardId === "string" &&
        typeof obj.memberName === "string"
      );
    });
  } catch {
    return [];
  }
}

function saveThreads(key: string, threads: ThreadInfo[]): void {
  try {
    localStorage.setItem(key, JSON.stringify(threads));
  } catch {
    // localStorage may be full or unavailable — silently ignore
  }
}

// ─── Hook ────────────────────────────────────────────────────────

function useWorkforceThreads(): UseWorkforceThreadsReturn {
  // Per signed-in user: these point at that user's advisor conversations, and a
  // global key handed them to whoever signed in next on the same browser. They
  // are also cleared at logout (`clearUserScopedStorage`).
  const { user } = useAuth();
  const userId = storageUserId(user);
  const storageKey = userScopedKey(STORAGE_KEY, userId);

  const [state, setState] = useState(() => ({
    key: storageKey,
    threads: loadThreads(userId),
  }));
  // A different user (or the profile arriving) means a different store: reload
  // from it rather than carry the previous user's list across.
  if (state.key !== storageKey) {
    setState({ key: storageKey, threads: loadThreads(userId) });
  }
  // On the render that switched keys React discards this output and renders
  // again with the reloaded list, so the stale value is never committed.
  const threads = state.threads;

  const setThreads = useCallback(
    (update: (prev: ThreadInfo[]) => ThreadInfo[]) => {
      setState((prev) => {
        const next = update(prev.threads);
        if (next !== prev.threads) saveThreads(prev.key, next);
        return next === prev.threads ? prev : { ...prev, threads: next };
      });
    },
    [],
  );

  const getThread = useCallback(
    (boardId: string, memberId: string): ThreadInfo | undefined => {
      return threads.find(
        (t) => t.boardId === boardId && t.memberId === memberId,
      );
    },
    [threads],
  );

  const registerThread = useCallback(
    (thread: Omit<ThreadInfo, "lastActivity">) => {
      setThreads((prev) => {
        const idx = prev.findIndex(
          (t) => t.boardId === thread.boardId && t.memberId === thread.memberId,
        );
        const entry: ThreadInfo = { ...thread, lastActivity: Date.now() };

        let next: ThreadInfo[];
        if (idx >= 0) {
          next = [...prev];
          next[idx] = entry;
        } else {
          next = [...prev, entry];
        }
        return next;
      });
    },
    [setThreads],
  );

  const updateActivity = useCallback(
    (boardId: string, memberId: string) => {
      setThreads((prev) => {
        const idx = prev.findIndex(
          (t) => t.boardId === boardId && t.memberId === memberId,
        );
        if (idx < 0) return prev;

        const next = [...prev];
        next[idx] = { ...next[idx]!, lastActivity: Date.now() };
        return next;
      });
    },
    [setThreads],
  );

  const removeThread = useCallback(
    (boardId: string, memberId: string) => {
      setThreads((prev) => {
        const next = prev.filter(
          (t) => !(t.boardId === boardId && t.memberId === memberId),
        );
        return next;
      });
    },
    [setThreads],
  );

  const getThreadsForBoard = useCallback(
    (boardId: string): ThreadInfo[] => {
      return threads.filter((t) => t.boardId === boardId);
    },
    [threads],
  );

  return {
    threads,
    getThread,
    registerThread,
    updateActivity,
    removeThread,
    getThreadsForBoard,
  };
}

export { useWorkforceThreads };
export type { ThreadInfo, UseWorkforceThreadsReturn };
