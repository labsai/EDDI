import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import {
  deliveredRowCount,
  isOpenPlaceholder,
  useGroupDiscussionStream,
  useGroupStreamStore,
} from "@/hooks/use-group-discussion-stream";
import type { GroupSSEEvent, TranscriptEntry } from "@/lib/api/groups";

/**
 * How a live group stream ends: turns that produce nothing, phases and runs
 * that end with members still "typing", connections that drop, and Stop.
 */

const mockStreamGroupDiscussion = vi.fn();
const mockCancel = vi.fn();

vi.mock("@/lib/api/groups", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/groups")>();
  return {
    ...original,
    streamGroupDiscussion: (...args: unknown[]) => mockStreamGroupDiscussion(...args),
  };
});

vi.mock("@/lib/api/hitl", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/hitl")>();
  return {
    ...original,
    cancelGroupDiscussion: (...args: unknown[]) => mockCancel(...args),
  };
});

const ev = (type: string, data: unknown): GroupSSEEvent =>
  ({ type, data: typeof data === "string" ? data : JSON.stringify(data) }) as GroupSSEEvent;

const start = ev("group_start", { groupConversationId: "gc-1", question: "Q?" });
const phase0 = ev("phase_start", { phaseIndex: 0, phaseName: "Opinions", phaseType: "OPINION" });
const speak = (agentId: string, phaseIndex = 0) =>
  ev("speaker_start", { agentId, displayName: agentId.toUpperCase(), phaseIndex, phaseName: "Opinions" });

async function run(events: GroupSSEEvent[]) {
  async function* gen() {
    for (const e of events) yield e;
  }
  mockStreamGroupDiscussion.mockReturnValue(gen());
  const hook = renderHook(() => useGroupDiscussionStream("g1"));
  await act(async () => {
    await hook.result.current.startStream("g1", "Q?");
  });
  return hook;
}

const member = (s: { transcript: TranscriptEntry[] }, agentId: string) =>
  s.transcript.filter((e) => e.speakerAgentId === agentId);

describe("group stream — turns that produce nothing", () => {
  beforeEach(() => {
    mockStreamGroupDiscussion.mockReset();
    mockCancel.mockReset();
  });
  afterEach(() => useGroupStreamStore.setState({ streams: {} }));

  it.each([
    ["TIMEOUT", "SKIPPED"],
    ["SKIPPED", "SKIPPED"],
    ["ERROR", "ERROR"],
  ])("closes the placeholder of a %s turn as %s, with no content", async (outcome, type) => {
    const { result } = await run([
      start,
      phase0,
      speak("a"),
      // The backend never carries raw error text here — but a stale client must
      // not show a `response` as something the member said either.
      ev("speaker_complete", { agentId: "a", displayName: "A", phaseIndex: 0, response: "boom", outcome }),
      ev("group_complete", { state: "COMPLETED", synthesizedAnswer: null }),
    ]);
    const [entry] = member(result.current.streamState, "a");
    expect(entry?.type).toBe(type);
    expect(entry?.content).toBeNull();
    expect(isOpenPlaceholder(entry!)).toBe(false);
  });

  it("closes a completion with no content from an older backend as SKIPPED", async () => {
    const { result } = await run([
      start,
      phase0,
      speak("a"),
      ev("speaker_complete", { agentId: "a", displayName: "A", phaseIndex: 0, response: null }),
    ]);
    expect(member(result.current.streamState, "a")[0]?.type).toBe("SKIPPED");
  });

  it("keeps an ordinary contribution as the member's reply", async () => {
    const { result } = await run([
      start,
      phase0,
      speak("a"),
      ev("speaker_complete", { agentId: "a", displayName: "A", phaseIndex: 0, response: "Yes." }),
    ]);
    const [entry] = member(result.current.streamState, "a");
    expect(entry?.type).toBe("OPINION");
    expect(entry?.content).toBe("Yes.");
  });

  it("treats an outcome it does not know as a contribution", async () => {
    const { result } = await run([
      start,
      phase0,
      speak("a"),
      ev("speaker_complete", { agentId: "a", displayName: "A", phaseIndex: 0, response: "Hi", outcome: "LATER" }),
    ]);
    expect(member(result.current.streamState, "a")[0]?.content).toBe("Hi");
  });

  /**
   * A backend without `speaker_complete.outcome` sends NO completion for a
   * PARALLEL member released by the batch deadline. Its placeholder then typed
   * for the rest of the discussion.
   */
  it("closes a placeholder the phase ended without completing", async () => {
    const { result } = await run([
      start,
      phase0,
      speak("a"),
      speak("b"),
      ev("speaker_complete", { agentId: "a", displayName: "A", phaseIndex: 0, response: "Done" }),
      ev("phase_complete", { phaseIndex: 0, phaseName: "Opinions" }),
      ev("phase_start", { phaseIndex: 1, phaseName: "Synthesis", phaseType: "SYNTHESIS" }),
      speak("mod", 1),
    ]);
    const s = result.current.streamState;
    expect(member(s, "b")[0]?.type).toBe("SKIPPED");
    expect(member(s, "a")[0]?.content).toBe("Done");
    // The next phase's speaker is not the phase that just ended.
    expect(isOpenPlaceholder(member(s, "mod")[0]!)).toBe(true);
  });

  it.each([
    ["group_complete", { state: "COMPLETED", synthesizedAnswer: null }],
    ["group_error", { error: "boom" }],
    ["cancelled", { reason: "x" }],
    ["awaiting_approval", { phaseIndex: 0, phaseName: "Opinions", reason: "r", granularity: "PHASE" }],
  ])("closes every open placeholder on %s", async (type, data) => {
    const { result } = await run([start, phase0, speak("a"), ev(type, data)]);
    expect(result.current.streamState.transcript.some(isOpenPlaceholder)).toBe(false);
    expect(result.current.streamState.activeSpeakers.size).toBe(0);
  });
});

describe("group stream — a connection that just stops", () => {
  beforeEach(() => {
    mockStreamGroupDiscussion.mockReset();
    mockCancel.mockReset();
  });
  afterEach(() => useGroupStreamStore.setState({ streams: {} }));

  it("marks the stream interrupted and leaves the members that may still be answering open", async () => {
    const { result } = await run([start, phase0, speak("a")]);
    const s = result.current.streamState;
    expect(s.isStreaming).toBe(false);
    expect(s.interrupted).toBe(true);
    expect(s.state).toBe("IN_PROGRESS");
    expect(s.error).toBeNull();
    expect(isOpenPlaceholder(member(s, "a")[0]!)).toBe(true);
  });

  it("is not interrupted when the server said how the run ended", async () => {
    const { result } = await run([start, ev("group_complete", { state: "COMPLETED", synthesizedAnswer: "x" })]);
    expect(result.current.streamState.interrupted).toBe(false);
  });

  it("counts delivered rows without the open placeholders", async () => {
    const { result } = await run([
      start,
      phase0,
      speak("a"),
      speak("b"),
      ev("speaker_complete", { agentId: "a", displayName: "A", phaseIndex: 0, response: "Yes" }),
    ]);
    // question + a's reply; b is still open.
    expect(deliveredRowCount(result.current.streamState.transcript)).toBe(2);
  });
});

describe("group stream — Stop cancels the discussion", () => {
  beforeEach(() => {
    mockStreamGroupDiscussion.mockReset();
    mockCancel.mockReset();
  });
  afterEach(() => useGroupStreamStore.setState({ streams: {} }));

  /** A stream that stays open until the test releases it or the signal aborts. */
  function openStream(events: GroupSSEEvent[]) {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    mockStreamGroupDiscussion.mockImplementation(
      (_g: string, _q: string, _u: unknown, signal: AbortSignal) =>
        (async function* () {
          for (const e of events) yield e;
          await Promise.race([
            gate,
            new Promise<void>((_, reject) =>
              signal.addEventListener("abort", () =>
                reject(new DOMException("aborted", "AbortError")),
              ),
            ),
          ]);
        })(),
    );
    return () => release();
  }

  it("cancels on the server, then closes the stream as CANCELLED", async () => {
    openStream([start, phase0, speak("a")]);
    mockCancel.mockResolvedValue(undefined);
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    let done: Promise<void> = Promise.resolve();
    act(() => {
      done = result.current.startStream("g1", "Q?");
    });
    await waitFor(() => expect(result.current.streamState.conversationId).toBe("gc-1"));

    let outcome: string | undefined;
    await act(async () => {
      outcome = await result.current.cancelStream();
      await done;
    });

    expect(mockCancel).toHaveBeenCalledWith("g1", "gc-1");
    expect(outcome).toBe("cancelled");
    const s = result.current.streamState;
    expect(s.isStreaming).toBe(false);
    expect(s.state).toBe("CANCELLED");
    expect(s.transcript.some(isOpenPlaceholder)).toBe(false);
  });

  it("keeps streaming when the cancel fails — the discussion is still running", async () => {
    const release = openStream([start]);
    mockCancel.mockRejectedValue(Object.assign(new Error("boom"), { status: 500 }));
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    let done: Promise<void> = Promise.resolve();
    act(() => {
      done = result.current.startStream("g1", "Q?");
    });
    await waitFor(() => expect(result.current.streamState.conversationId).toBe("gc-1"));

    await act(async () => {
      await expect(result.current.cancelStream()).rejects.toThrow("boom");
    });
    expect(result.current.streamState.isStreaming).toBe(true);
    expect(result.current.streamState.state).toBe("IN_PROGRESS");

    await act(async () => {
      release();
      await done;
    });
  });

  it("closes the stream without claiming CANCELLED when the run had already ended (409)", async () => {
    openStream([start]);
    mockCancel.mockRejectedValue(Object.assign(new Error("terminal"), { status: 409 }));
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    let done: Promise<void> = Promise.resolve();
    act(() => {
      done = result.current.startStream("g1", "Q?");
    });
    await waitFor(() => expect(result.current.streamState.conversationId).toBe("gc-1"));

    let outcome: string | undefined;
    await act(async () => {
      outcome = await result.current.cancelStream();
      await done;
    });
    expect(outcome).toBe("alreadyEnded");
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.state).not.toBe("CANCELLED");
  });

  /**
   * Stop pressed before `group_start` named the conversation: there is nothing
   * to address yet, and closing the connection then would leave the run going
   * with no way to reach it. The cancel waits for the id instead.
   */
  it("sends the cancel as soon as group_start names the conversation", async () => {
    let deliverStart: () => void = () => {};
    const startGate = new Promise<void>((resolve) => {
      deliverStart = resolve;
    });
    mockStreamGroupDiscussion.mockImplementation(
      (_g: string, _q: string, _u: unknown, signal: AbortSignal) =>
        (async function* () {
          await startGate;
          yield start;
          await new Promise<void>((_, reject) =>
            signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError"))),
          );
        })(),
    );
    mockCancel.mockResolvedValue(undefined);
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    let done: Promise<void> = Promise.resolve();
    act(() => {
      done = result.current.startStream("g1", "Q?");
    });

    let outcome: string | undefined;
    await act(async () => {
      outcome = await result.current.cancelStream();
    });
    expect(outcome).toBe("pending");
    expect(mockCancel).not.toHaveBeenCalled();
    expect(result.current.streamState.cancelRequested).toBe(true);

    await act(async () => {
      deliverStart();
      await done;
    });
    expect(mockCancel).toHaveBeenCalledWith("g1", "gc-1");
    expect(result.current.streamState.state).toBe("CANCELLED");
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.cancelRequested).toBe(false);
  });

  it("has nothing to cancel without a live stream", async () => {
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    let outcome: string | undefined;
    await act(async () => {
      outcome = await result.current.cancelStream();
    });
    expect(outcome).toBe("nothingToCancel");
    expect(mockCancel).not.toHaveBeenCalled();
  });

  /**
   * A dropped connection leaves the run going with no stream to cancel through.
   * Stop must still reach it, and the "connection lost, keeps updating" notice
   * must not outlive the cancel.
   */
  it("cancels an interrupted discussion by its id and clears the interruption", async () => {
    const { result } = await run([start, phase0, speak("a")]);
    expect(result.current.streamState.interrupted).toBe(true);
    mockCancel.mockResolvedValue(undefined);

    let outcome: string | undefined;
    await act(async () => {
      outcome = await result.current.cancelStream("gc-1");
    });

    expect(mockCancel).toHaveBeenCalledWith("g1", "gc-1");
    expect(outcome).toBe("cancelled");
    expect(result.current.streamState.state).toBe("CANCELLED");
    expect(result.current.streamState.interrupted).toBe(false);
    expect(result.current.streamState.transcript.some(isOpenPlaceholder)).toBe(false);
  });

  it("cancels a discussion this tab never streamed, without inventing a stream for it", async () => {
    mockCancel.mockResolvedValue(undefined);
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    let outcome: string | undefined;
    await act(async () => {
      outcome = await result.current.cancelStream("gc-adopted");
    });
    expect(mockCancel).toHaveBeenCalledWith("g1", "gc-adopted");
    expect(outcome).toBe("cancelled");
    expect(useGroupStreamStore.getState().streams.g1).toBeUndefined();
  });
});
