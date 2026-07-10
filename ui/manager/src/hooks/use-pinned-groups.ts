import { useState, useCallback } from "react";

const STORAGE_KEY = "boardroom-pinned-groups";

function readPinned(): Set<string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return new Set(raw ? JSON.parse(raw) : []);
  } catch {
    return new Set();
  }
}

function writePinned(pinned: Set<string>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...pinned]));
  } catch {
    // localStorage may be full or unavailable
  }
}

export function usePinnedGroups() {
  const [pinned, setPinned] = useState<Set<string>>(readPinned);

  const togglePin = useCallback((groupId: string) => {
    setPinned((prev) => {
      const next = new Set(prev);
      if (next.has(groupId)) {
        next.delete(groupId);
      } else {
        next.add(groupId);
      }
      writePinned(next);
      return next;
    });
  }, []);

  const isPinned = useCallback(
    (groupId: string) => pinned.has(groupId),
    [pinned],
  );

  return { pinned, togglePin, isPinned };
}
