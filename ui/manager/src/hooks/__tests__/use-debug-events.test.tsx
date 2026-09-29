import { beforeEach, describe, expect, it } from "vitest";
import { act } from "@testing-library/react";
import {
  useDebugStore,
  resolveLiveTurnSteps,
  type PipelineEvent,
  type PipelineTurn,
} from "@/hooks/use-debug-events";
import type { AuditEntry } from "@/lib/api/audit";

describe("useDebugStore", () => {
  beforeEach(() => {
    // Reset store between tests
    act(() => {
      useDebugStore.getState().reset();
    });
    localStorage.clear();
  });

  it("has correct initial state", () => {
    const state = useDebugStore.getState();
    expect(state.turns).toEqual([]);
    expect(state.currentTurnEvents).toEqual([]);
    expect(state.currentTurnStart).toBe(0);
    expect(state.activeTab).toBe("pipeline");
    expect(state.selectedTurnIndex).toBeNull();
  });

  it("addEvent appends to currentTurnEvents", () => {
    const event: PipelineEvent = {
      type: "task_start",
      taskId: "t1",
      taskType: "langchain",
      index: 0,
      timestamp: 1000,
    };
    act(() => {
      useDebugStore.getState().addEvent(event);
    });
    const state = useDebugStore.getState();
    expect(state.currentTurnEvents).toHaveLength(1);
    expect(state.currentTurnEvents[0]).toEqual(event);
    expect(state.currentTurnStart).toBe(1000);
  });

  it("addEvent preserves start time from first event", () => {
    act(() => {
      useDebugStore.getState().addEvent({
        type: "task_start",
        taskId: "t1",
        taskType: "langchain",
        index: 0,
        timestamp: 1000,
      });
      useDebugStore.getState().addEvent({
        type: "task_complete",
        taskId: "t1",
        taskType: "langchain",
        index: 0,
        durationMs: 500,
        timestamp: 1500,
      });
    });
    expect(useDebugStore.getState().currentTurnStart).toBe(1000);
  });

  it("finalizeTurn creates a turn from current events", () => {
    act(() => {
      useDebugStore.getState().addEvent({
        type: "task_start",
        taskId: "t1",
        taskType: "langchain",
        index: 0,
        timestamp: 1000,
      });
      useDebugStore.getState().addEvent({
        type: "task_complete",
        taskId: "t1",
        taskType: "langchain",
        index: 0,
        durationMs: 500,
        timestamp: 1500,
      });
      useDebugStore.getState().finalizeTurn();
    });

    const state = useDebugStore.getState();
    expect(state.turns).toHaveLength(1);
    expect(state.turns[0]!.events).toHaveLength(2);
    expect(state.turns[0]!.totalDurationMs).toBe(500);
    expect(state.turns[0]!.turnIndex).toBe(0);
    expect(state.currentTurnEvents).toEqual([]);
    expect(state.currentTurnStart).toBe(0);
  });

  it("finalizeTurn does nothing when no events", () => {
    act(() => {
      useDebugStore.getState().finalizeTurn();
    });
    expect(useDebugStore.getState().turns).toEqual([]);
  });

  it("setDebugOpen saves to localStorage", () => {
    act(() => {
      useDebugStore.getState().setDebugOpen(true);
    });
    expect(useDebugStore.getState().isDebugOpen).toBe(true);
    expect(localStorage.getItem("eddi-debug-open")).toBe("true");

    act(() => {
      useDebugStore.getState().setDebugOpen(false);
    });
    expect(useDebugStore.getState().isDebugOpen).toBe(false);
    expect(localStorage.getItem("eddi-debug-open")).toBe("false");
  });

  it("toggleDebug flips state and persists", () => {
    act(() => {
      useDebugStore.getState().setDebugOpen(false);
    });
    expect(useDebugStore.getState().isDebugOpen).toBe(false);

    act(() => {
      useDebugStore.getState().toggleDebug();
    });
    expect(useDebugStore.getState().isDebugOpen).toBe(true);
    expect(localStorage.getItem("eddi-debug-open")).toBe("true");
  });

  it("setActiveTab changes tab", () => {
    act(() => {
      useDebugStore.getState().setActiveTab("costs");
    });
    expect(useDebugStore.getState().activeTab).toBe("costs");
  });

  it("setSelectedTurn sets the turn index", () => {
    act(() => {
      useDebugStore.getState().setSelectedTurn(2);
    });
    expect(useDebugStore.getState().selectedTurnIndex).toBe(2);

    act(() => {
      useDebugStore.getState().setSelectedTurn(null);
    });
    expect(useDebugStore.getState().selectedTurnIndex).toBeNull();
  });

  it("toggleShowActivity toggles and persists", () => {
    // Default is true
    act(() => {
      useDebugStore.getState().toggleShowActivity();
    });
    expect(useDebugStore.getState().showActivity).toBe(false);
    expect(localStorage.getItem("eddi-show-activity")).toBe("false");

    act(() => {
      useDebugStore.getState().toggleShowActivity();
    });
    expect(useDebugStore.getState().showActivity).toBe(true);
    expect(localStorage.getItem("eddi-show-activity")).toBe("true");
  });

  it("reset clears turns but preserves UI prefs", () => {
    act(() => {
      useDebugStore.getState().addEvent({
        type: "task_start",
        taskId: "t1",
        taskType: "langchain",
        index: 0,
        timestamp: 1000,
      });
      useDebugStore.getState().finalizeTurn();
      useDebugStore.getState().setSelectedTurn(0);
      useDebugStore.getState().reset();
    });

    const state = useDebugStore.getState();
    expect(state.turns).toEqual([]);
    expect(state.currentTurnEvents).toEqual([]);
    expect(state.currentTurnStart).toBe(0);
    expect(state.selectedTurnIndex).toBeNull();
  });
});

describe("useDebugStore.bindConversation", () => {
  const ev = (taskType: string, durationMs: number): PipelineEvent => ({
    type: "task_complete",
    taskId: taskType,
    taskType,
    index: 0,
    durationMs,
    timestamp: 1,
  });

  beforeEach(() => {
    act(() => {
      useDebugStore.getState().reset();
      useDebugStore.setState({ boundConversationId: null });
    });
  });

  it("keeps the turn being recorded when the conversation id first arrives", () => {
    act(() => {
      useDebugStore.getState().addEvent(ev("llm", 5));
      useDebugStore.getState().bindConversation("conv-a");
    });
    expect(useDebugStore.getState().currentTurnEvents).toHaveLength(1);
  });

  it("clears recorded turns when the debugger moves to another conversation", () => {
    act(() => {
      useDebugStore.getState().bindConversation("conv-a");
      useDebugStore.getState().addEvent(ev("llm", 5));
      useDebugStore.getState().finalizeTurn();
      useDebugStore.getState().addEvent(ev("llm", 6));
    });
    expect(useDebugStore.getState().turns).toHaveLength(1);

    act(() => useDebugStore.getState().bindConversation("conv-b"));
    const s = useDebugStore.getState();
    expect(s.turns).toEqual([]);
    expect(s.boundConversationId).toBe("conv-b");
    // A switch mid-stream: the running turn belongs to conv-a, not conv-b.
    expect(s.currentTurnEvents).toEqual([]);
  });

  it("re-binding the same conversation changes nothing", () => {
    act(() => {
      useDebugStore.getState().bindConversation("conv-a");
      useDebugStore.getState().addEvent(ev("llm", 5));
      useDebugStore.getState().finalizeTurn();
      useDebugStore.getState().bindConversation("conv-a");
    });
    expect(useDebugStore.getState().turns).toHaveLength(1);
  });
});

describe("resolveLiveTurnSteps", () => {
  const ev = (taskType: string, durationMs: number): PipelineEvent => ({
    type: "task_complete",
    taskId: taskType,
    taskType,
    index: 0,
    durationMs,
    timestamp: 1,
  });
  const turn = (...events: PipelineEvent[]): PipelineTurn => ({
    turnIndex: 0,
    events,
    totalDurationMs: 0,
    startTime: 0,
  });
  const audit = (stepIndex: number, taskType: string, durationMs: number) =>
    ({ stepIndex, taskType, durationMs }) as unknown as AuditEntry;

  it("matches the one step with exactly the same (taskType, durationMs) pairs", () => {
    const entries = [
      audit(0, "parser", 3),
      audit(0, "llm", 100),
      audit(3, "parser", 42),
      audit(3, "llm", 250),
    ];
    expect(
      resolveLiveTurnSteps([turn(ev("parser", 42), ev("llm", 250))], entries)
    ).toEqual([{ status: "matched", stepIndex: 3 }]);
  });

  // A rule-based turn is all fast tasks, so every step has the same
  // fingerprint. The resolver used to pick the newest step and show its data
  // with full confidence.
  it("refuses to pick between two steps with the same all-zero fingerprint", () => {
    const entries = [
      audit(1, "parser", 0),
      audit(1, "output", 0),
      audit(2, "parser", 0),
      audit(2, "output", 0),
    ];
    expect(
      resolveLiveTurnSteps([turn(ev("parser", 0), ev("output", 0))], entries)
    ).toEqual([{ status: "ambiguous" }]);
  });

  it("requires the exact task count — a step with more entries is not a match", () => {
    const entries = [audit(4, "parser", 42), audit(4, "llm", 250), audit(4, "output", 1)];
    expect(
      resolveLiveTurnSteps([turn(ev("parser", 42), ev("llm", 250))], entries)
    ).toEqual([{ status: "pending" }]);
  });

  it("claims each step once, in order, so a repeated fingerprint maps to successive steps", () => {
    const entries = [audit(0, "greet", 5), audit(1, "llm", 7), audit(2, "llm", 9)];
    expect(
      resolveLiveTurnSteps([turn(ev("llm", 7)), turn(ev("llm", 9))], entries)
    ).toEqual([
      { status: "matched", stepIndex: 1 },
      { status: "matched", stepIndex: 2 },
    ]);
    // The earlier match raises the floor: a later identical turn cannot go back.
    expect(
      resolveLiveTurnSteps(
        [turn(ev("llm", 9)), turn(ev("llm", 7))],
        entries
      )[1]
    ).toEqual({ status: "pending" });
  });

  it("reports a turn the ledger has not written yet as pending", () => {
    expect(resolveLiveTurnSteps([turn(ev("llm", 7))], [])).toEqual([
      { status: "pending" },
    ]);
  });
});
