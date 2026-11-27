import { describe, expect, it, vi } from "vitest";
import type { GroupSSEEvent } from "@eddi/core/groups";
import { GroupDiscussion, initialGroupStreamState, type GroupTransport } from "./group-discussion";

/*
 * The team kind's controller on its own: no React, no store library, a fake
 * transport. The apps' half (the Zustand binding, the hook, the board and the
 * workspace) is covered by their suites — ui/manager/src/hooks/__tests__/
 * use-group-discussion-stream*.test.ts and the Workforce board tests.
 */

/** A stream the test feeds frame by frame, then ends or breaks. */
function feed() {
  const queue: Array<GroupSSEEvent | Error | null> = [];
  let wake: (() => void) | null = null;
  const push = (item: GroupSSEEvent | Error | null) => {
    queue.push(item);
    wake?.();
  };
  async function* iterate(): AsyncGenerator<GroupSSEEvent> {
    for (;;) {
      if (queue.length === 0) await new Promise<void>((resolve) => (wake = resolve));
      wake = null;
      const next = queue.shift();
      if (next === null || next === undefined) return;
      if (next instanceof Error) throw next;
      yield next;
    }
  }
  return {
    events: iterate(),
    send: (type: string, data: unknown) => push({ type: type as GroupSSEEvent["type"], data: JSON.stringify(data) }),
    end: () => push(null),
    fail: (e: Error) => push(e),
  };
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

function transport(overrides: Partial<GroupTransport<string>> = {}): GroupTransport<string> {
  return {
    discuss: vi.fn(),
    continueRound: vi.fn(),
    approve: vi.fn(),
    cancel: vi.fn().mockResolvedValue(undefined),
    isAlreadyEnded: (e) => e instanceof Error && e.message === "409",
    ...overrides,
  };
}

describe("GroupDiscussion", () => {
  it("builds the transcript of a run from its events and settles on group_complete", async () => {
    const stream = feed();
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events }) });
    const seen: boolean[] = [];
    discussion.subscribe(() => seen.push(discussion.getSnapshot().isStreaming));

    const run = discussion.start("Should we ship?");
    expect(discussion.getSnapshot()).toMatchObject({ isStreaming: true, state: "IN_PROGRESS", connected: false });

    stream.send("group_start", { groupConversationId: "gc-1", question: "Should we ship?" });
    stream.send("phase_start", { phaseIndex: 0, phaseName: "Opinions", phaseType: "OPINION" });
    stream.send("speaker_start", { agentId: "a1", displayName: "Analyst", phaseIndex: 0 });
    stream.send("speaker_complete", { agentId: "a1", displayName: "Analyst", response: "Yes.", phaseIndex: 0 });
    stream.send("group_complete", { synthesizedAnswer: "Ship it.", state: "COMPLETED" });
    await run;

    const state = discussion.getSnapshot();
    expect(state.conversationId).toBe("gc-1");
    expect(state.state).toBe("COMPLETED");
    expect(state.isStreaming).toBe(false);
    expect(state.connected).toBe(true);
    expect(state.synthesizedAnswer).toBe("Ship it.");
    expect(state.transcript.map((e) => [e.type, e.content])).toEqual([
      ["QUESTION", "Should we ship?"],
      ["OPINION", "Yes."],
    ]);
    expect(state.activeSpeakers.size).toBe(0);
    expect(seen[seen.length - 1]).toBe(false);
  });

  it("drops the late writes of a run a newer one replaced", async () => {
    const first = feed();
    const second = feed();
    const discuss = vi.fn().mockReturnValueOnce(first.events).mockReturnValueOnce(second.events);
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss }) });

    const firstRun = discussion.start("one");
    first.send("group_start", { groupConversationId: "gc-1", question: "one" });
    await tick();
    const secondRun = discussion.start("two");
    // The replaced stream still delivers a frame and then fails: neither may land.
    first.send("group_complete", { synthesizedAnswer: "stale", state: "COMPLETED" });
    first.fail(new Error("socket closed"));
    await firstRun;

    expect(discussion.getSnapshot()).toMatchObject({ isStreaming: true, conversationId: null, synthesizedAnswer: null });
    second.send("group_start", { groupConversationId: "gc-2", question: "two" });
    second.send("group_complete", { synthesizedAnswer: "fresh", state: "COMPLETED" });
    await secondRun;
    expect(discussion.getSnapshot()).toMatchObject({ conversationId: "gc-2", synthesizedAnswer: "fresh", state: "COMPLETED" });
  });

  it("marks a refusal before the first frame FAILED, and a drop after it interrupted", async () => {
    const refused = new GroupDiscussion({
      groupId: "g1",
      transport: transport({
        discuss: () => ({
          [Symbol.asyncIterator]: () => ({
            next: () => Promise.reject(new Error("question is required (HTTP 400)")),
          }),
        }),
      }),
    });
    await refused.start("");
    expect(refused.getSnapshot()).toMatchObject({ state: "FAILED", error: "question is required (HTTP 400)", isStreaming: false });

    const stream = feed();
    const dropped = new GroupDiscussion({ groupId: "g2", transport: transport({ discuss: () => stream.events }) });
    const run = dropped.start("q");
    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    stream.fail(new Error("proxy timeout"));
    await run;
    // The run may still be going on the server: not a failure.
    expect(dropped.getSnapshot()).toMatchObject({ state: "IN_PROGRESS", interrupted: true, isStreaming: false, error: null });
  });

  it("sends a Stop pressed before the conversation had an id as soon as group_start names it", async () => {
    const stream = feed();
    const cancel = vi.fn().mockResolvedValue(undefined);
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events, cancel }) });

    const run = discussion.start("q");
    await expect(discussion.cancel()).resolves.toBe("pending");
    expect(discussion.getSnapshot().cancelRequested).toBe(true);
    expect(cancel).not.toHaveBeenCalled();

    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    await run;
    expect(cancel).toHaveBeenCalledWith("g1", "gc-1");
    expect(discussion.getSnapshot()).toMatchObject({ state: "CANCELLED", isStreaming: false, cancelRequested: false });
  });

  it("leaves the outcome to the stored document when the discussion had already ended (409)", async () => {
    const stream = feed();
    const cancel = vi.fn().mockRejectedValue(new Error("409"));
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events, cancel }) });
    const run = discussion.start("q");
    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    await tick();

    await expect(discussion.cancel()).resolves.toBe("alreadyEnded");
    expect(discussion.getSnapshot()).toMatchObject({ state: "IN_PROGRESS", isStreaming: false });
    stream.end();
    await run;
  });

  it("keeps following the run when the Stop itself is refused, and rethrows", async () => {
    const stream = feed();
    const cancel = vi.fn().mockRejectedValue(new Error("403"));
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events, cancel }) });
    const run = discussion.start("q");
    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    await tick();

    await expect(discussion.cancel()).rejects.toThrow("403");
    expect(discussion.getSnapshot()).toMatchObject({ isStreaming: true, cancelRequested: false });
    stream.end();
    await run;
  });

  it("resolves whenAccepted on the first frame, and false when the request is refused", async () => {
    const stream = feed();
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events }) });
    await expect(discussion.whenAccepted()).resolves.toBe(false);

    const run = discussion.start("q");
    const accepted = discussion.whenAccepted();
    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    await expect(accepted).resolves.toBe(true);
    stream.end();
    await run;
  });

  it("appends a continued round to the stored transcript instead of replacing it", async () => {
    const stream = feed();
    const continueRound = vi.fn().mockReturnValue(stream.events);
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ continueRound }) });
    const seed = {
      id: "gc-1",
      transcript: [
        { speakerAgentId: "user", speakerDisplayName: "User", content: "first", type: "QUESTION", phaseIndex: -1, phaseName: null, timestamp: "t", errorReason: null, targetAgentId: null },
      ],
    } as never;

    const run = discussion.continueRound("gc-1", "second", seed);
    expect(continueRound).toHaveBeenCalledWith("g1", "gc-1", "second", expect.any(AbortSignal));
    stream.send("round_start", { groupConversationId: "gc-1", question: "second" });
    stream.send("group_complete", { synthesizedAnswer: "done", state: "COMPLETED" });
    await run;

    const state = discussion.getSnapshot();
    expect(state.transcript.map((e) => e.content)).toEqual(["first", "second"]);
    expect(state.roundStartIndex).toBe(1);
  });

  it("drops the state on reset, back to the shared empty state", async () => {
    const stream = feed();
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events }) });
    const run = discussion.start("q");
    discussion.reset();
    // The fake ignores the abort signal, so its late frame is the supersession test.
    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    stream.end();
    await run;
    expect(discussion.hasStream()).toBe(false);
    expect(discussion.getSnapshot()).toBe(initialGroupStreamState);
  });

  it("keeps its state in the host's store when given one", async () => {
    const stream = feed();
    let value: ReturnType<GroupDiscussion["getSnapshot"]> | undefined;
    const store = {
      get: () => value,
      set: vi.fn((next) => (value = next)),
      remove: vi.fn(() => (value = undefined)),
      subscribe: () => () => {},
    };
    const discussion = new GroupDiscussion({ groupId: "g1", transport: transport({ discuss: () => stream.events }), store });
    const run = discussion.start("q");
    stream.send("group_start", { groupConversationId: "gc-1", question: "q" });
    stream.end();
    await run;
    expect(store.set).toHaveBeenCalled();
    expect(value?.conversationId).toBe("gc-1");
    discussion.reset();
    expect(store.remove).toHaveBeenCalled();
  });
});
