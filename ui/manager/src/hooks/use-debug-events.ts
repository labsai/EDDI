import { create } from "zustand";

// ==================== Types ====================

export interface ToolTraceEntry {
  type: "tool_call" | "tool_result";
  tool: string;
  arguments?: string;
  result?: string;
}

export interface PipelineEvent {
  type: "task_start" | "task_complete" | "cascade_step_start" | "cascade_escalation";
  taskId: string;
  taskType: string;
  index: number;
  durationMs?: number;
  actions?: string[];
  confidence?: number;
  toolTrace?: ToolTraceEntry[];
  // ── Cascade-specific (model cascade SSE events) ──
  /** Model name for the cascade step (e.g. "gpt-4o-mini"). */
  modelName?: string;
  /** Total number of steps in the cascade. */
  totalSteps?: number;
  /** 0-based index of the cascade step this event refers to. */
  stepIndex?: number;
  /** Escalation source step (0-based). */
  fromStep?: number;
  /** Escalation destination step (0-based). */
  toStep?: number;
  /** Confidence threshold that triggered an escalation. */
  threshold?: number;
  /** Why the cascade escalated: low_confidence | timeout | error | retryable_error. */
  reason?: string;
  timestamp: number;
}

export interface PipelineTurn {
  turnIndex: number;
  events: PipelineEvent[];
  totalDurationMs: number;
  startTime: number;
}

export interface CascadeStepInfo {
  stepIndex: number;
  modelName?: string;
  modelType?: string;
  /** Set on steps that were reached via an escalation. */
  escalatedFrom?: {
    fromStep?: number;
    confidence?: number;
    threshold?: number;
    reason?: string;
  };
}

/**
 * Fold `cascade_step_start` / `cascade_escalation` events into an ordered
 * per-step view (model name + escalation info). Returns an empty list when
 * the turn had no cascade activity.
 */
export function buildCascadeSteps(events: PipelineEvent[]): CascadeStepInfo[] {
  const byStep = new Map<number, CascadeStepInfo>();
  for (const e of events) {
    if (e.type === "cascade_step_start" && e.stepIndex != null) {
      byStep.set(e.stepIndex, {
        ...(byStep.get(e.stepIndex) ?? { stepIndex: e.stepIndex }),
        modelName: e.modelName,
        modelType: e.taskType,
      });
    } else if (e.type === "cascade_escalation" && e.toStep != null) {
      byStep.set(e.toStep, {
        ...(byStep.get(e.toStep) ?? { stepIndex: e.toStep }),
        escalatedFrom: {
          fromStep: e.fromStep,
          confidence: e.confidence,
          threshold: e.threshold,
          reason: e.reason,
        },
      });
    }
  }
  return Array.from(byStep.values()).sort((a, b) => a.stepIndex - b.stepIndex);
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
  showActivity: boolean; // inline activity cards in chat

  // Actions
  addEvent: (event: PipelineEvent) => void;
  finalizeTurn: () => void;
  setDebugOpen: (open: boolean) => void;
  toggleDebug: () => void;
  setActiveTab: (tab: DebugTab) => void;
  setSelectedTurn: (index: number | null) => void;
  toggleShowActivity: () => void;
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

const loadActivityPref = (): boolean => {
  try {
    return localStorage.getItem("eddi-show-activity") !== "false";
  } catch {
    return true;
  }
};

const saveActivityPref = (show: boolean) => {
  try {
    localStorage.setItem("eddi-show-activity", String(show));
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
  showActivity: loadActivityPref(),

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

  toggleShowActivity: () =>
    set((s) => {
      const next = !s.showActivity;
      saveActivityPref(next);
      return { showActivity: next };
    }),

  reset: () =>
    set({
      turns: [],
      currentTurnEvents: [],
      currentTurnStart: 0,
      selectedTurnIndex: null,
    }),
}));
