import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  useGroupDiscussionStream,
  useGroupStreamStore,
} from "@/hooks/use-group-discussion-stream";
import type { GroupSSEEvent } from "@/lib/api/groups";

const mockStreamGroupDiscussion = vi.fn();
const mockStreamGroupContinue = vi.fn();

vi.mock("@/lib/api/groups", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/groups")>();
  return {
    ...original,
    streamGroupDiscussion: (...args: unknown[]) => mockStreamGroupDiscussion(...args),
    streamGroupContinue: (...args: unknown[]) => mockStreamGroupContinue(...args),
  };
});

/** Feed a fixed list of SSE events through the store's consumer. */
function events(list: GroupSSEEvent[]) {
  return (async function* () {
    for (const e of list) yield e;
  })();
}

const ev = (type: string, data: unknown): GroupSSEEvent =>
  ({ type, data: JSON.stringify(data) } as GroupSSEEvent);

describe("group stream — Wave 0/1 events", () => {
  beforeEach(() => {
    mockStreamGroupDiscussion.mockReset();
    mockStreamGroupContinue.mockReset();
  });

  afterEach(() => {
    useGroupStreamStore.setState({ streams: {} });
  });

  it("records a convergence check that has not converged", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("convergence_checked", {
          phaseIndex: 1, phaseName: "Discussion", repeat: 1,
          agreementScore: 0.62, converged: false, reason: "still moving",
        }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    const progress = result.current.streamState.convergence.get(1)!;
    expect(progress.agreementScore).toBeCloseTo(0.62);
    expect(progress.converged).toBe(false);
    expect(progress.repeatsSkipped).toBeNull();
    expect(progress.reason).toBe("still moving");
  });

  it("reads the -1 sentinel as 'no judge ran', not as a score", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("convergence_checked", {
          phaseIndex: 0, phaseName: "P", repeat: 2,
          agreementScore: -1, converged: true, reason: "everyone abstained",
        }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    // Rendering "-1.00 agreement" would be worse than rendering nothing.
    expect(result.current.streamState.convergence.get(0)!.agreementScore).toBeNull();
  });

  it("keeps the score from the check when the reached event follows", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("convergence_checked", {
          phaseIndex: 2, phaseName: "Round 2", repeat: 1,
          agreementScore: 0.91, converged: true, reason: "agreed",
        }),
        ev("convergence_reached", {
          phaseIndex: 2, phaseName: "Round 2", repeat: 1,
          repeatsSkipped: 3, reason: "agreed",
        }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    const progress = result.current.streamState.convergence.get(2)!;
    expect(progress.agreementScore).toBeCloseTo(0.91);
    expect(progress.repeatsSkipped).toBe(3);
    expect(progress.converged).toBe(true);
  });

  it("keeps convergence records for different phases apart", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("convergence_checked", { phaseIndex: 0, phaseName: "A", repeat: 1, agreementScore: 0.4, converged: false, reason: "a" }),
        ev("convergence_checked", { phaseIndex: 3, phaseName: "B", repeat: 1, agreementScore: 0.9, converged: true, reason: "b" }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.convergence.get(0)!.phaseName).toBe("A");
    expect(result.current.streamState.convergence.get(3)!.phaseName).toBe("B");
  });

  it("stores a decision record", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("decision_reached", {
          decision: {
            type: "VERDICT", outcome: "CON wins", winner: "CON",
            tally: { PRO: 4, CON: 9 }, dissents: [],
            method: "debate-judgment", decidedAtPhase: "Judgment",
          },
        }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.decision).toMatchObject({
      type: "VERDICT", winner: "CON", method: "debate-judgment",
    });
  });

  it("defaults a missing dissents array so the card cannot crash on it", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([ev("decision_reached", { decision: { type: "VERDICT", winner: "PRO" } })]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.decision!.dissents).toEqual([]);
  });

  it("survives a decision_reached with no decision payload", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("decision_reached", { decision: null }),
        ev("group_complete", { state: "COMPLETED", synthesizedAnswer: "done" }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.decision).toBeNull();
    // The stream must still have reached its terminal event.
    expect(result.current.streamState.synthesizedAnswer).toBe("done");
  });

  it("clears the previous round's decision when continuing", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("group_start", { groupConversationId: "gc1", groupId: "g1", question: "q", style: "DEBATE", totalPhases: 5, memberAgentIds: [] }),
        ev("decision_reached", { decision: { type: "VERDICT", winner: "PRO", dissents: [] } }),
        ev("group_complete", { state: "COMPLETED", synthesizedAnswer: "round 1" }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });
    expect(result.current.streamState.decision).not.toBeNull();

    // A round that produces no verdict of its own must not keep displaying the
    // previous round's — the backend clears both fields on continue.
    mockStreamGroupContinue.mockReturnValue(events([]));
    await act(async () => { await result.current.continueStream("g1", "gc1", "q2"); });

    expect(result.current.streamState.decision).toBeNull();
    expect(result.current.streamState.convergence.size).toBe(0);
  });

  it("forwards attachments to the streaming start endpoint", async () => {
    mockStreamGroupDiscussion.mockReturnValue(events([]));
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    const attachments = [{ fileName: "a.pdf", mimeType: "application/pdf", data: "QQ==" }];

    await act(async () => { await result.current.startStream("g1", "q", attachments); });

    expect(mockStreamGroupDiscussion).toHaveBeenCalledWith(
      "g1", "q", undefined, expect.anything(), attachments,
    );
  });

  // I6 — a HUMAN member's turn is up.
  it("records a human_input_requested event as a terminal AWAITING_HUMAN_INPUT pause", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("human_input_requested", {
          memberId: "human-1", displayName: "Alex", phaseIndex: 2, phaseName: "Deliberation",
        }),
        // A real stream stops here (terminal) — anything after should never apply.
        ev("phase_start", { phaseIndex: 3, phaseName: "Should not apply", phaseType: "SYNTHESIS", participants: "MODERATOR" }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.state).toBe("AWAITING_HUMAN_INPUT");
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.humanInputRequest).toEqual({
      memberId: "human-1", displayName: "Alex", phaseIndex: 2, phaseName: "Deliberation",
    });
    // Terminal — the phase_start after it must never have been processed.
    expect(result.current.streamState.currentPhase).toBeNull();
  });

  it("clears a stale humanInputRequest when a new discussion starts", async () => {
    mockStreamGroupDiscussion.mockReturnValueOnce(
      events([ev("human_input_requested", { memberId: "h1", displayName: "Alex", phaseIndex: 0, phaseName: "P" })]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q1"); });
    expect(result.current.streamState.humanInputRequest).not.toBeNull();

    mockStreamGroupDiscussion.mockReturnValueOnce(events([]));
    await act(async () => { await result.current.startStream("g1", "q2"); });
    expect(result.current.streamState.humanInputRequest).toBeNull();
  });

  // I8 — retrospective lessons harvested into group memory.
  it("accumulates retro_recorded events without being terminal", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("retro_recorded", { groupId: "g1", phaseName: "Retro", lessonsStored: 3 }),
        // Not terminal — later events must still be processed.
        ev("group_complete", { state: "COMPLETED", synthesizedAnswer: "done" }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.retroRecorded).toEqual([
      { groupId: "g1", phaseName: "Retro", lessonsStored: 3 },
    ]);
    expect(result.current.streamState.state).toBe("COMPLETED");
  });

  it("keeps a zero-lesson retro as real signal, not nothing", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([ev("retro_recorded", { groupId: "g1", phaseName: "Retro", lessonsStored: 0 })]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.retroRecorded).toHaveLength(1);
    expect(result.current.streamState.retroRecorded[0]!.lessonsStored).toBe(0);
  });

  // I17 — shared artifact writes.
  it("accumulates artifact_updated events without being terminal, distinguishing create from update", async () => {
    mockStreamGroupDiscussion.mockReturnValue(
      events([
        ev("artifact_updated", {
          artifactId: "a1", name: "notes.md", type: "MARKDOWN", version: 1,
          editorAgentId: "agent-1", status: "DRAFT", created: true,
        }),
        ev("artifact_updated", {
          artifactId: "a1", name: "notes.md", type: "MARKDOWN", version: 2,
          editorAgentId: "agent-2", status: "DRAFT", created: false,
        }),
        ev("group_complete", { state: "COMPLETED", synthesizedAnswer: "done" }),
      ]),
    );
    const { result } = renderHook(() => useGroupDiscussionStream("g1"));
    await act(async () => { await result.current.startStream("g1", "q"); });

    expect(result.current.streamState.artifactUpdates).toHaveLength(2);
    expect(result.current.streamState.artifactUpdates[0]!.created).toBe(true);
    expect(result.current.streamState.artifactUpdates[1]!.created).toBe(false);
    expect(result.current.streamState.artifactUpdates[1]!.version).toBe(2);
  });
});
