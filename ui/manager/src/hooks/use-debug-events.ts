import { create } from "zustand";

// ==================== Types ====================

export interface PipelineEvent {
  type: "task_start" | "task_complete" | "cascade_step_start" | "cascade_escalation";
  taskId: string;
  taskType: string;
  index: number;
  durationMs?: number;
  actions?: string[];
  confidence?: number;
  timestamp: number;
}

export interface PipelineTurn {
  turnIndex: number;
  events: PipelineEvent[];
  totalDurationMs: number;
  startTime: number;
}

export type DebugTab = "pipeline" | "costs" | "memory" | "logs" | "prompt";

interface DebugState {
  // Pipeline event data
  turns: PipelineTurn[];
  currentTurnEvents: PipelineEvent[];
  currentTurnStart: number;

  // UI state
  isDebugOpen: boolean;
  activeTab: DebugTab;
  selectedTurnIndex: number | null; // null = current/latest

  // Actions
  addEvent: (event: PipelineEvent) => void;
  finalizeTurn: () => void;
  setDebugOpen: (open: boolean) => void;
  toggleDebug: () => void;
  setActiveTab: (tab: DebugTab) => void;
  setSelectedTurn: (index: number | null) => void;
  reset: () => void;
}

// ==================== Helpers ====================

const loadDebugPref = (): boolean => {
  try {
    return localStorage.getItem("eddi-debug-open") === "true";
  } catch {
    return false;
  }
};

const saveDebugPref = (open: boolean) => {
  try {
    localStorage.setItem("eddi-debug-open", String(open));
  } catch {
    /* noop */
  }
};

// ==================== Store ====================

export const useDebugStore = create<DebugState>((set) => ({
  turns: [],
  currentTurnEvents: [],
  currentTurnStart: 0,
  isDebugOpen: loadDebugPref(),
  activeTab: "pipeline",
  selectedTurnIndex: null,

  addEvent: (event) =>
    set((s) => ({
      currentTurnEvents: [...s.currentTurnEvents, event],
      currentTurnStart: s.currentTurnStart || event.timestamp,
    })),

  finalizeTurn: () =>
    set((s) => {
      if (s.currentTurnEvents.length === 0) return s;

      const events = s.currentTurnEvents;
      const totalDurationMs = events.reduce(
        (sum, e) => sum + (e.durationMs ?? 0),
        0,
      );

      const newTurn: PipelineTurn = {
        turnIndex: s.turns.length,
        events,
        totalDurationMs,
        startTime: s.currentTurnStart,
      };

      return {
        turns: [...s.turns, newTurn],
        currentTurnEvents: [],
        currentTurnStart: 0,
        selectedTurnIndex: null,
      };
    }),

  setDebugOpen: (open) => {
    saveDebugPref(open);
    set({ isDebugOpen: open });
  },

  toggleDebug: () =>
    set((s) => {
      const next = !s.isDebugOpen;
      saveDebugPref(next);
      return { isDebugOpen: next };
    }),

  setActiveTab: (tab) => set({ activeTab: tab }),

  setSelectedTurn: (index) => set({ selectedTurnIndex: index }),

  reset: () =>
    set({
      turns: [],
      currentTurnEvents: [],
      currentTurnStart: 0,
      selectedTurnIndex: null,
    }),
}));
