import { useState, useCallback } from "react";

// ─── Types ───────────────────────────────────────────────────────

interface ThreadInfo {
  memberId: string; // agentId of the advisor
  memberName: string;
  conversationId: string;
  boardId: string;
  lastActivity: number; // timestamp
}

interface UseBoardroomThreadsReturn {
  threads: ThreadInfo[];
  getThread: (boardId: string, memberId: string) => ThreadInfo | undefined;
  registerThread: (thread: Omit<ThreadInfo, "lastActivity">) => void;
  updateActivity: (boardId: string, memberId: string) => void;
  removeThread: (boardId: string, memberId: string) => void;
  getThreadsForBoard: (boardId: string) => ThreadInfo[];
}

// ─── Constants ───────────────────────────────────────────────────

const STORAGE_KEY = "boardroom-threads";

// ─── Helpers ─────────────────────────────────────────────────────

function loadThreads(): ThreadInfo[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed as ThreadInfo[];
  } catch {
    return [];
  }
}

function saveThreads(threads: ThreadInfo[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(threads));
  } catch {
    // localStorage may be full or unavailable — silently ignore
  }
}

// ─── Hook ────────────────────────────────────────────────────────

function useBoardroomThreads(): UseBoardroomThreadsReturn {
  const [threads, setThreads] = useState<ThreadInfo[]>(loadThreads);

  const persist = useCallback((next: ThreadInfo[]) => {
    setThreads(next);
    saveThreads(next);
  }, []);

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
        saveThreads(next);
        return next;
      });
    },
    [],
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
        saveThreads(next);
        return next;
      });
    },
    [],
  );

  const removeThread = useCallback(
    (boardId: string, memberId: string) => {
      setThreads((prev) => {
        const next = prev.filter(
          (t) => !(t.boardId === boardId && t.memberId === memberId),
        );
        saveThreads(next);
        return next;
      });
    },
    [],
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

export { useBoardroomThreads };
export type { ThreadInfo, UseBoardroomThreadsReturn };
