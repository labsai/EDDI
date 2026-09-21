import { create } from "zustand";

// ==================== Types ====================

interface CommandPaletteState {
  isOpen: boolean;
  recentPages: Array<{ path: string; label: string }>;

  // Actions
  open: () => void;
  close: () => void;
  toggle: () => void;
  addRecentPage: (path: string, label: string) => void;
}

// ==================== Helpers ====================

const MAX_RECENT = 5;

function loadRecent(): Array<{ path: string; label: string }> {
  try {
    const raw = localStorage.getItem("eddi-recent-pages");
    if (raw) return JSON.parse(raw);
  } catch {
    /* noop */
  }
  return [];
}

function saveRecent(pages: Array<{ path: string; label: string }>) {
  try {
    localStorage.setItem("eddi-recent-pages", JSON.stringify(pages));
  } catch {
    /* noop */
  }
}

// ==================== Store ====================

export const useCommandPalette = create<CommandPaletteState>((set) => ({
  isOpen: false,
  recentPages: loadRecent(),

  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
  toggle: () => set((s) => ({ isOpen: !s.isOpen })),

  addRecentPage: (path, label) =>
    set((s) => {
      const filtered = s.recentPages.filter((p) => p.path !== path);
      const updated = [{ path, label }, ...filtered].slice(0, MAX_RECENT);
      saveRecent(updated);
      return { recentPages: updated };
    }),
}));
