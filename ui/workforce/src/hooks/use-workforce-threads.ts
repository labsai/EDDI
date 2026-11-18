import { useState, useCallback } from "react";
import { useAuth } from "@eddi/app-kit/hooks/use-auth";
import { LEGACY_THREADS_KEY, principalOf, threadsStorageKey } from "@eddi/app-kit/lib/workforce-threads-storage";

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


// ─── Helpers ─────────────────────────────────────────────────────

function loadThreads(storageKey: string): ThreadInfo[] {
  try {
    // The key older versions wrote for everybody names no one: not this person's.
    localStorage.removeItem(LEGACY_THREADS_KEY);
    const raw = localStorage.getItem(storageKey);
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

function saveThreads(storageKey: string, threads: ThreadInfo[]): void {
  try {
    localStorage.setItem(storageKey, JSON.stringify(threads));
  } catch {
    // localStorage may be full or unavailable — silently ignore
  }
}

// ─── Hook ────────────────────────────────────────────────────────

function useWorkforceThreads(): UseWorkforceThreadsReturn {
  // The pointers are this person's: the key names the signed-in principal.
  const { user } = useAuth();
  const storageKey = threadsStorageKey(principalOf(user));
  const [threads, setThreads] = useState<ThreadInfo[]>(() => loadThreads(storageKey));



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
        saveThreads(storageKey, next);
        return next;
      });
    },
    [storageKey],
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
        saveThreads(storageKey, next);
        return next;
      });
    },
    [storageKey],
  );

  const removeThread = useCallback(
    (boardId: string, memberId: string) => {
      setThreads((prev) => {
        const next = prev.filter(
          (t) => !(t.boardId === boardId && t.memberId === memberId),
        );
        saveThreads(storageKey, next);
        return next;
      });
    },
    [storageKey],
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
