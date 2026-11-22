import { describe, expect, it, vi } from "vitest";
import type { ToolResultEvent } from "@eddi/core/tool-events";
import { ToolActivityLog, toolCardPositions, toolNames } from "./tool-activity-log";

const call = (tool: string, ok = true, summary = ""): ToolResultEvent => ({ tool, ok, summary });

describe("ToolActivityLog", () => {
  it("files each call under its conversation and step, in order", () => {
    const log = new ToolActivityLog();
    log.record("c1", 1, call("search"));
    log.record("c1", 1, call("send_email", false));
    log.record("c1", 2, call("search"));
    log.record("c2", 1, call("other"));
    expect(log.forStep("c1", 1)?.results.map((r) => r.tool)).toEqual(["search", "send_email"]);
    expect(log.forStep("c1", 2)?.results.map((r) => r.tool)).toEqual(["search"]);
    expect(log.forStep("c2", 1)?.results.map((r) => r.tool)).toEqual(["other"]);
    expect(log.forStep("c2", 2)).toBeUndefined();
  });

  it("starts a turn's step empty and forgets the undone steps after it", () => {
    const log = new ToolActivityLog();
    log.record("c1", 1, call("a"));
    log.record("c1", 2, call("b"));
    log.record("c1", 3, call("c"));
    // An undo took steps 2 and 3 back; a new question now takes step 2.
    log.beginTurn("c1", 2);
    expect(log.forStep("c1", 1)?.results).toHaveLength(1);
    expect(log.forStep("c1", 2)).toBeUndefined();
    expect(log.forStep("c1", 3)).toBeUndefined();
    log.record("c1", 2, call("d"));
    expect(toolNames(log.forStep("c1", 2))).toEqual(["d"]);
  });

  it("keeps an undone step until a new turn takes it, so redo finds it", () => {
    const log = new ToolActivityLog();
    log.record("c1", 2, call("a"));
    // Undo changes nothing here; the transcript simply stops showing step 2.
    expect(log.forStep("c1", 2)).toBeDefined();
  });

  it("clears one step (a rerun without a stream)", () => {
    const log = new ToolActivityLog();
    log.record("c1", 1, call("a"));
    log.record("c1", 2, call("b"));
    log.clearStep("c1", 2);
    expect(log.forStep("c1", 2)).toBeUndefined();
    expect(log.forStep("c1", 1)).toBeDefined();
  });

  it("caps a turn and counts what it dropped", () => {
    const log = new ToolActivityLog({ maxPerTurn: 2 });
    for (const tool of ["a", "b", "c", "d"]) log.record("c1", 1, call(tool));
    const turn = log.forStep("c1", 1)!;
    expect(turn.results.map((r) => r.tool)).toEqual(["a", "b"]);
    expect(turn.dropped).toBe(2);
  });

  it("forgets the least recently changed conversation past the cap", () => {
    const log = new ToolActivityLog({ maxConversations: 2 });
    log.record("c1", 1, call("a"));
    log.record("c2", 1, call("b"));
    log.record("c1", 2, call("c")); // c1 is now the most recent
    log.record("c3", 1, call("d"));
    expect(log.forStep("c2", 1)).toBeUndefined();
    expect(log.forStep("c1", 1)).toBeDefined();
    expect(log.forStep("c3", 1)).toBeDefined();
  });

  it("ignores a call with no step to file it under", () => {
    const log = new ToolActivityLog();
    const listener = vi.fn();
    log.subscribe(listener);
    log.record("c1", null, call("a"));
    log.beginTurn("c1", null);
    expect(log.turns("c1").size).toBe(0);
    expect(listener).not.toHaveBeenCalled();
  });

  it("notifies on a change and keeps the same turns object otherwise", () => {
    const log = new ToolActivityLog();
    const listener = vi.fn();
    const unsubscribe = log.subscribe(listener);
    const before = log.turns("c1");
    log.beginTurn("c1", 1); // nothing to forget
    expect(log.turns("c1")).toBe(before);
    expect(listener).not.toHaveBeenCalled();
    log.record("c1", 1, call("a"));
    expect(listener).toHaveBeenCalledTimes(1);
    expect(log.turns("c1")).not.toBe(before);
    expect(log.getSnapshot()).toBe(1);
    unsubscribe();
    log.record("c1", 1, call("b"));
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("names each tool once, in the order they first ran", () => {
    expect(toolNames({ results: [call("a"), call("b"), call("a")], dropped: 0 })).toEqual(["a", "b"]);
    expect(toolNames(undefined)).toEqual([]);
  });
});

describe("toolCardPositions", () => {
  const turns = (...steps: number[]) => new Map(steps.map((s) => [s, { results: [call("t")], dropped: 0 }]));

  it("puts each turn's cards under its own question", () => {
    const messages = [
      { role: "agent", content: "Hello" }, // step 0
      { role: "user", content: "Q1" }, // step 1
      { role: "agent", content: "A1" },
      { role: "user", content: "Q2" }, // step 2
      { role: "agent", content: "A2" },
    ];
    expect([...toolCardPositions(messages, turns(1, 2))]).toEqual([
      [1, 1],
      [3, 2],
    ]);
  });

  it("skips notices, reads stored step indexes, and puts a step without a question after its last message", () => {
    const messages = [
      { role: "agent", content: "Hi", stepIndex: 0 },
      { role: "agent", content: "More", stepIndex: 0 },
      { role: "agent", content: "Stopped", kind: "notice" },
      { role: "user", content: "Q", stepIndex: 4 },
    ];
    expect([...toolCardPositions(messages, turns(0, 4))]).toEqual([
      [1, 0],
      [3, 4],
    ]);
  });

  it("shows nothing for a step the transcript does not show (undone)", () => {
    const messages = [{ role: "user", content: "Q1" }];
    expect(toolCardPositions(messages, turns(2)).size).toBe(0);
  });
});
