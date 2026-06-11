import { act } from "@testing-library/react";
import {
  useDebugStore,
  type PipelineEvent,
} from "@/hooks/use-debug-events";

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
    expect(state.turns[0].events).toHaveLength(2);
    expect(state.turns[0].totalDurationMs).toBe(500);
    expect(state.turns[0].turnIndex).toBe(0);
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
