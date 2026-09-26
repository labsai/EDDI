import { create } from "zustand";
import type { AuditEntry } from "@/lib/api/audit";

// ==================== Types ====================

export interface ToolTraceEntry {
  // "tool_error" is real on the wire: the backend interleaves it for budget,
  // quota and pause-cap refusals (with no matching tool_result — and for the
  // pause cap, no preceding tool_call either). The type omitting it is what
  // let the trace UI zip calls to results by INDEX and mis-attribute every
  // outcome after the first refusal.
  type: "tool_call" | "tool_result" | "tool_error";
  tool: string;
  arguments?: string;
  result?: string;
  /** tool_error entries: the refusal reason. */
  error?: string;
}

export interface PipelineEvent {
  type:
    | "task_start"
    | "task_complete"
    | "task_failed"
    | "cascade_step_start"
    | "cascade_escalation";
  taskId: string;
  taskType: string;
  index: number;
  durationMs?: number;
  actions?: string[];
  confidence?: number;
  toolTrace?: ToolTraceEntry[];
  /** task_failed: classified failure kind (timeout|transport|rate_limit|content_filter|unknown). */
  errorType?: string;
  /** task_failed: redacted human-readable error summary. */
  errorSummary?: string;
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
  /** Position in the list being shown (0-based). NOT a conversation step. */
  turnIndex: number;
  /**
   * The conversation step (audit `stepIndex`) this turn is, when known. Set for
   * turns rebuilt from the audit trail; unset for live turns, whose step is
   * resolved against the audit entries by {@link resolveLiveTurnSteps}.
   */
  stepIndex?: number;
  events: PipelineEvent[];
  totalDurationMs: number;
  startTime: number;
}

export interface CascadeStepInfo {
  stepIndex: number;
  modelName?: string;
  modelType?: string;
  /**
   * Set on the step whose low confidence caused the cascade to escalate onward.
   * The confidence/threshold belong to THIS step (the one that was rejected), so
   * they render on its own row rather than the destination's.
   */
  escalation?: {
    toStep?: number;
    confidence?: number;
    threshold?: number;
    reason?: string;
  };
}

/**
 * Fold `cascade_step_start` / `cascade_escalation` events into an ordered
 * per-step view. Escalation info is attached to the SOURCE step (fromStep) —
 * the one that was evaluated and found lacking — so the final accepted step
 * stays clean. Returns an empty list when the turn had no cascade activity.
 */
export function buildCascadeSteps(events: PipelineEvent[]): CascadeStepInfo[] {
  const byStep = new Map<number, CascadeStepInfo>();
  const ensure = (i: number) => byStep.get(i) ?? { stepIndex: i };
  for (const e of events) {
    if (e.type === "cascade_step_start" && e.stepIndex != null) {
      byStep.set(e.stepIndex, { ...ensure(e.stepIndex), modelName: e.modelName, modelType: e.taskType });
    } else if (e.type === "cascade_escalation" && e.fromStep != null) {
      byStep.set(e.fromStep, {
        ...ensure(e.fromStep),
        escalation: {
          toStep: e.toStep,
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
  /**
   * Tool names from live `tool_call` SSE events, in call order, current turn
   * only. Kept OUT of currentTurnEvents: the authoritative per-task record
   * (with arguments and results) still arrives in task_complete's toolTrace,
   * and storing both in one list would double-count. This list exists solely
   * so the status line can say "Using {tool}…" while the turn is running.
   */
  liveToolCalls: string[];
  /**
   * True once TOKENS have arrived since the last `tool_call`, i.e. the model is
   * writing again and the tool phase is over.
   *
   * There is no live "tool finished" event — the per-call result only reaches
   * the client in the turn-end toolTrace — so the newest call was rendered as
   * running forever, still spinning under a finished answer. Resumed output IS
   * the completion signal.
   */
  liveToolsSettled: boolean;

  // UI state
  isDebugOpen: boolean;
  activeTab: DebugTab;
  selectedTurnIndex: number | null; // null = current/latest
  /**
   * The conversation the recorded turns belong to. The store is global and
   * nothing used to clear it, so after switching conversation (or agent) the
   * debugger kept showing — and adding to — the previous conversation's turns.
   */
  boundConversationId: string | null;
  showActivity: boolean; // inline activity cards in chat

  // Actions
  addEvent: (event: PipelineEvent) => void;
  addToolCall: (tool: string) => void;
  markToolsSettled: () => void;
  finalizeTurn: () => void;
  setDebugOpen: (open: boolean) => void;
  toggleDebug: () => void;
  setActiveTab: (tab: DebugTab) => void;
  setSelectedTurn: (index: number | null) => void;
  toggleShowActivity: () => void;
  /** Point the debugger at a conversation; switching away clears the turns. */
  bindConversation: (conversationId: string | null) => void;
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
  liveToolCalls: [],
  liveToolsSettled: false,
  isDebugOpen: loadDebugPref(),
  activeTab: "pipeline",
  selectedTurnIndex: null,
  boundConversationId: null,
  showActivity: loadActivityPref(),

  addEvent: (event) =>
    set((s) => ({
      currentTurnEvents: [...s.currentTurnEvents, event],
      currentTurnStart: s.currentTurnStart || event.timestamp,
    })),

  addToolCall: (tool) =>
    set((s) => ({ liveToolCalls: [...s.liveToolCalls, tool], liveToolsSettled: false })),

  // Idempotent on purpose: this runs on EVERY token, so returning the same
  // object once the flag is set keeps it from re-rendering the tree per token.
  markToolsSettled: () =>
    set((s) => (s.liveToolsSettled ? s : { ...s, liveToolsSettled: true })),

  finalizeTurn: () =>
    set((s) => {
      if (s.currentTurnEvents.length === 0) {
        // No pipeline events, but a stale live-tool list must still not leak
        // into the next turn's status line.
        return s.liveToolCalls.length ? { ...s, liveToolCalls: [], liveToolsSettled: false } : s;
      }

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
        liveToolCalls: [],
        liveToolsSettled: false,
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

  bindConversation: (conversationId) =>
    set((s) => {
      if (s.boundConversationId === conversationId) return s;
      // null → X is the conversation being created for the turn that is
      // already recording: keep that turn.
      if (s.boundConversationId === null) {
        return { ...s, boundConversationId: conversationId };
      }
      // A different conversation: drop everything, the running turn included —
      // a switch mid-stream leaves that turn with the conversation it came
      // from, not the one being switched to.
      return {
        ...s,
        boundConversationId: conversationId,
        turns: [],
        currentTurnEvents: [],
        currentTurnStart: 0,
        liveToolCalls: [],
        liveToolsSettled: false,
        selectedTurnIndex: null,
      };
    }),

  reset: () =>
    set({
      turns: [],
      currentTurnEvents: [],
      currentTurnStart: 0,
      liveToolCalls: [],
      liveToolsSettled: false,
      selectedTurnIndex: null,
    }),
}));

/**
 * Workflow steps that are plumbing, not activity — filtered out of end-user
 * chat unless the step made a tool call or failed.
 *
 * Matched LOWERCASED against taskType: the authoritative list is each
 * ILifecycleTask.getType() in the EDDI backend — httpCalls, mcpCalls,
 * langchain, expressions, output, properties, behavior_rules — note the
 * camelCase ids, which an exact match silently missed (the Platform Operator
 * rendered a wall of "41 steps" for a greeting). The extra entries keep
 * older/renamed variants covered. Lives here rather than in a component file
 * so both ChatActivity and the panel's inline indicator share ONE classifier
 * (two hand-synced sets already drifted once), and react-refresh stays happy.
 */
const INTERNAL_INFRA_TASKS = new Set([
  "expressions",
  "behavior_rules",
  "langchain",
  "dictionary",
  "properties",
  "propertysetter",
  "parser",
  "output",
  "httpcalls",
  "apicalls",
  "mcpcalls",
  "ai.labs.expressions",
  "ai.labs.behavior_rules",
  "ai.labs.langchain",
  "ai.labs.dictionary",
  "ai.labs.propertysetter",
  "ai.labs.parser",
  "ai.labs.output",
  "ai.labs.httpcalls",
  "ai.labs.apicalls",
]);

/** The one shared predicate for "is this pipeline task plumbing?". */
export function isInternalTask(taskType: string): boolean {
  return INTERNAL_INFRA_TASKS.has(taskType.toLowerCase());
}

/** How a live turn was matched to the audit ledger. */
export type AuditStepMatch =
  | { status: "matched"; stepIndex: number }
  /** No step carries this turn yet — the ledger flushes asynchronously. */
  | { status: "pending" }
  /** Several steps fit equally well; showing any of them would be a guess. */
  | { status: "ambiguous" };

function fingerprint(pairs: Array<[string, number]>): string {
  return pairs
    .map(([type, ms]) => `${type}|${ms}`)
    .sort()
    .join(",");
}

/**
 * Match FINISHED live turns to audit steps, in order, never guessing.
 *
 * Live turns used to be matched to audit entries by `turnIndex` — a count of
 * turns seen in THIS session — against the audit's `stepIndex`, the
 * conversation's own step number, so every turn showed another turn's costs
 * and model.
 *
 * The backend measures each task's duration once and sends the same number to
 * the stream (`task_complete.durationMs`) and to the audit ledger
 * (`AuditEntry.durationMs`), so the multiset of (taskType, durationMs) pairs
 * fingerprints a turn — but only when it is distinctive. A rule-based turn is
 * typically parser|0, behavior|0, output|1, templating|0, identical on every
 * step. So:
 * - a step matches only if its entries are EXACTLY the turn's completed tasks
 *   (same count, same pairs), not merely a superset;
 * - more than one unclaimed candidate is "ambiguous" — no audit data is shown;
 * - live turns are chronological and so are steps: each step is claimed at
 *   most once, and a later turn only considers steps after the last one
 *   claimed;
 * - no candidate yet is "pending" (the ledger flushes every few seconds), and
 *   the caller re-reads until it resolves.
 * In-flight turns are never resolved: a partial fingerprint fits almost any
 * earlier step.
 */
export function resolveLiveTurnSteps(
  turns: PipelineTurn[],
  auditEntries: AuditEntry[] | undefined
): AuditStepMatch[] {
  const bySteps = new Map<number, Array<[string, number]>>();
  for (const a of auditEntries ?? []) {
    if (a.stepIndex == null) continue;
    const list = bySteps.get(a.stepIndex) ?? [];
    list.push([a.taskType, a.durationMs]);
    bySteps.set(a.stepIndex, list);
  }
  const stepPrints = [...bySteps.entries()]
    .map(([step, pairs]) => ({ step, print: fingerprint(pairs) }))
    .sort((x, y) => x.step - y.step);

  let floor = -1;
  return turns.map((turn) => {
    const pairs: Array<[string, number]> = turn.events
      .filter((e) => e.type === "task_complete" && e.durationMs != null)
      .map((e) => [e.taskType, e.durationMs!]);
    if (pairs.length === 0) return { status: "ambiguous" as const };
    const print = fingerprint(pairs);
    const candidates = stepPrints.filter(
      (s) => s.step > floor && s.print === print
    );
    if (candidates.length === 0) return { status: "pending" as const };
    if (candidates.length > 1) return { status: "ambiguous" as const };
    floor = candidates[0]!.step;
    return { status: "matched" as const, stepIndex: floor };
  });
}
