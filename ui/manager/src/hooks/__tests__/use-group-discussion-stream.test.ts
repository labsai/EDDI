import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  useGroupDiscussionStream,
  useStreamingGroupIds,
  useGroupStreamStore,
} from "@/hooks/use-group-discussion-stream";

const mockStreamGroupDiscussion = vi.fn();
const mockStreamGroupApproval = vi.fn();
const mockStreamGroupContinue = vi.fn();

vi.mock("@/lib/api/groups", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/groups")>();
  return {
    ...original,
    streamGroupDiscussion: (...args: unknown[]) => mockStreamGroupDiscussion(...args),
    streamGroupApproval: (...args: unknown[]) => mockStreamGroupApproval(...args),
    streamGroupContinue: (...args: unknown[]) => mockStreamGroupContinue(...args),
  };
});

describe("useGroupDiscussionStream", () => {
  beforeEach(() => {
    mockStreamGroupDiscussion.mockReset();
    mockStreamGroupApproval.mockReset();
    mockStreamGroupContinue.mockReset();
  });

  // The store is module-level and survives the whole file — without this, a
  // test that fails mid-stream leaks its state into every test after it.
  afterEach(() => {
    useGroupStreamStore.setState({ streams: {} });
  });

  it("returns initial state", () => {
    const { result } = renderHook(() => useGroupDiscussionStream());

    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.conversationId).toBeNull();
    expect(result.current.streamState.state).toBe("CREATED");
    expect(result.current.streamState.transcript).toEqual([]);
    expect(result.current.streamState.currentPhase).toBeNull();
    expect(result.current.streamState.activeSpeakers.size).toBe(0);
    expect(result.current.streamState.synthesizedAnswer).toBeNull();
    expect(result.current.streamState.error).toBeNull();
    expect(result.current.streamState.startedAt).toBeNull();
  });

  it("provides startStream and abortStream callbacks", () => {
    const { result } = renderHook(() => useGroupDiscussionStream());

    expect(typeof result.current.startStream).toBe("function");
    expect(typeof result.current.abortStream).toBe("function");
  });

  it("abortStream sets isStreaming to false", () => {
    const { result } = renderHook(() => useGroupDiscussionStream());

    act(() => {
      result.current.abortStream();
    });

    expect(result.current.streamState.isStreaming).toBe(false);
  });

  it("startStream and abortStream are stable across renders", () => {
    const { result, rerender } = renderHook(() => useGroupDiscussionStream());

    const startStream1 = result.current.startStream;
    const abortStream1 = result.current.abortStream;

    rerender();

    expect(result.current.startStream).toBe(startStream1);
    expect(result.current.abortStream).toBe(abortStream1);
  });

  it("streams discussion events and updates state successfully", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-123", question: "Is 2+2=4?" }) };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Opinion Gathering", phaseType: "OPINION" }) };
      yield { type: "speaker_start", data: JSON.stringify({ agentId: "agent-1", displayName: "MathBot", phaseIndex: 0, phaseName: "Opinion Gathering" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "agent-1", displayName: "MathBot", phaseIndex: 0, response: "Yes, 2+2=4." }) };
      yield { type: "phase_complete", data: "{}" };
      yield { type: "synthesis_start", data: "" };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "The final answer is yes." }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.state).toBe("COMPLETED");
    expect(result.current.streamState.conversationId).toBe("conv-123");
    expect(result.current.streamState.synthesizedAnswer).toBe("The final answer is yes.");
    expect(result.current.streamState.transcript).toHaveLength(2); // User question + speaker response
    expect(result.current.streamState.currentPhase?.name).toBe("Opinion Gathering");
  });

  it("handles speaker_complete without matching speaker_start placeholder", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-123", question: "Is 2+2=4?" }) };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Opinion Gathering", phaseType: "CRITIQUE" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "agent-1", displayName: "MathBot", phaseIndex: 0, content: "Direct reply" }) };
      yield { type: "group_complete", data: "{}" }; // empty data
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.transcript).toHaveLength(2); // user + direct reply
    expect(result.current.streamState.transcript[1]?.content).toBe("Direct reply");
    expect(result.current.streamState.transcript[1]?.type).toBe("CRITIQUE");
  });

  it("handles stream error event", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-123", question: "Is 2+2=4?" }) };
      yield { type: "group_error", data: JSON.stringify({ error: "Failed to fetch model" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.state).toBe("FAILED");
    expect(result.current.streamState.error).toBe("Failed to fetch model");
  });

  it("handles stream error event with raw string data", async () => {
    async function* mockEvents() {
      yield { type: "group_error", data: "Raw server crash description" };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.state).toBe("FAILED");
    expect(result.current.streamState.error).toBe("Raw server crash description");
  });

  it("handles parsing errors gracefully", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: "invalid-json" };
      yield { type: "phase_start", data: "invalid-json" };
      yield { type: "speaker_start", data: "invalid-json" };
      yield { type: "speaker_complete", data: "invalid-json" };
      yield { type: "phase_complete", data: "invalid-json" };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.conversationId).toBeNull();
  });

  it("handles exception thrown in generator", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-123", question: "Is 2+2=4?" }) };
      throw new Error("Network interrupted");
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.state).toBe("FAILED");
    expect(result.current.streamState.error).toBe("Network interrupted");
  });

  it("swallows AbortError exception in generator", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-123", question: "Is 2+2=4?" }) };
      throw new DOMException("The operation was aborted.", "AbortError");
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Is 2+2=4?");
    });

    expect(result.current.streamState.state).toBe("IN_PROGRESS");
    expect(result.current.streamState.isStreaming).toBe(false);
  });

  it("handles task_plan_created event and populates taskPlan state", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-200", question: "Plan tasks" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({
          tasks: [
            { id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 },
            { id: "t2", subject: "Summarize", assignedTo: "Agent Beta", priority: 1 },
          ],
        }),
      };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Plan tasks");
    });

    expect(result.current.streamState.taskPlan).toEqual([
      { id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 },
      { id: "t2", subject: "Summarize", assignedTo: "Agent Beta", priority: 1 },
    ]);
  });

  it("handles task_verified event and populates taskVerifications map", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-201", question: "Verify tasks" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({
          tasks: [{ id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 }],
        }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Verification", phaseType: "VERIFY" }) };
      yield { type: "task_verified", data: JSON.stringify({ taskId: "t1", passed: true, feedback: "Good" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Verified" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Verify tasks");
    });

    const verification = result.current.streamState.taskVerifications.get("t1");
    expect(verification).toEqual({ passed: true, feedback: "Good" });
  });

  it("tracks task in-progress during EXECUTE phase on speaker_start", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-202", question: "Execute tasks" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({
          tasks: [{ id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 }],
        }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Execution", phaseType: "EXECUTE" }) };
      yield { type: "speaker_start", data: JSON.stringify({ agentId: "agent-1", displayName: "Agent Alpha", phaseIndex: 0, phaseName: "Execution" }) };
      // Do NOT yield speaker_complete — we want to observe in-progress state
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "In progress" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Execute tasks");
    });

    expect(result.current.streamState.tasksInProgress.has("t1")).toBe(true);
  });

  it("tracks task completion during EXECUTE phase on speaker_complete", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-203", question: "Complete tasks" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({
          tasks: [{ id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 }],
        }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Execution", phaseType: "EXECUTE" }) };
      yield { type: "speaker_start", data: JSON.stringify({ agentId: "agent-1", displayName: "Agent Alpha", phaseIndex: 0, phaseName: "Execution" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "agent-1", displayName: "Agent Alpha", phaseIndex: 0, response: "Task done" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "All done" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Complete tasks");
    });

    expect(result.current.streamState.tasksInProgress.size).toBe(0);
    expect(result.current.streamState.tasksCompleted.has("t1")).toBe(true);
  });

  it("resets task state on new stream", async () => {
    // First stream with task events
    async function* mockEvents1() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-204", question: "First stream" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({
          tasks: [{ id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 }],
        }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Verification", phaseType: "VERIFY" }) };
      yield { type: "task_verified", data: JSON.stringify({ taskId: "t1", passed: true, feedback: "Good" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done" }) };
    }

    // Second stream — no task events
    async function* mockEvents2() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-205", question: "Second stream" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done again" }) };
    }

    mockStreamGroupDiscussion.mockReturnValueOnce(mockEvents1());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "First stream");
    });

    // Confirm task state was populated from first stream
    expect(result.current.streamState.taskPlan).not.toBeNull();
    expect(result.current.streamState.taskVerifications.size).toBe(1);

    // Start a new stream
    mockStreamGroupDiscussion.mockReturnValueOnce(mockEvents2());

    await act(async () => {
      await result.current.startStream("group-1", "Second stream");
    });

    // All task state should be reset
    expect(result.current.streamState.taskPlan).toBeNull();
    expect(result.current.streamState.taskVerifications.size).toBe(0);
    expect(result.current.streamState.tasksInProgress.size).toBe(0);
    expect(result.current.streamState.tasksCompleted.size).toBe(0);
  });

  it("task tracking does not trigger outside EXECUTE phase", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-206", question: "Opinion phase" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({
          tasks: [{ id: "t1", subject: "Research", assignedTo: "Agent Alpha", priority: 0 }],
        }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Opinion Gathering", phaseType: "OPINION" }) };
      yield { type: "speaker_start", data: JSON.stringify({ agentId: "agent-1", displayName: "Agent Alpha", phaseIndex: 0, phaseName: "Opinion Gathering" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "agent-1", displayName: "Agent Alpha", phaseIndex: 0, response: "My opinion" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Opinions gathered" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Opinion phase");
    });

    expect(result.current.streamState.tasksInProgress.size).toBe(0);
    expect(result.current.streamState.tasksCompleted.size).toBe(0);
  });

  // ── HITL: pause / resume / cancel ──

  it("pauses on awaiting_approval and records the pause", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-hitl-1", question: "Approve?" }) };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 1, phaseName: "Execute", phaseType: "EXECUTE" }) };
      yield { type: "awaiting_approval", data: JSON.stringify({ phaseIndex: 1, phaseName: "Execute", reason: "Needs sign-off", granularity: "PHASE" }) };
    }
    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());
    await act(async () => {
      await result.current.startStream("group-1", "Approve?");
    });

    expect(result.current.streamState.state).toBe("AWAITING_APPROVAL");
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.hitlPause).toEqual({
      phaseIndex: 1,
      phaseName: "Execute",
      reason: "Needs sign-off",
      granularity: "PHASE",
    });
  });

  it("handles cancelled event", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-hitl-2", question: "Cancel?" }) };
      yield { type: "cancelled", data: JSON.stringify({ reason: "User aborted", cancelledBy: "manager-user" }) };
    }
    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());
    await act(async () => {
      await result.current.startStream("group-1", "Cancel?");
    });

    expect(result.current.streamState.state).toBe("CANCELLED");
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.cancelInfo).toEqual({ reason: "User aborted", cancelledBy: "manager-user" });
  });

  it("approveAndStream submits the decision and streams the resumed discussion", async () => {
    async function* paused() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-hitl-3", question: "Q" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "a1", displayName: "Alpha", phaseIndex: 0, response: "Pre-pause answer" }) };
      yield { type: "awaiting_approval", data: JSON.stringify({ phaseIndex: 0, phaseName: "Opinion", reason: "r", granularity: "PHASE" }) };
    }
    async function* resumed() {
      yield { type: "hitl_resume", data: JSON.stringify({ verdict: "APPROVED", decidedBy: "manager-user" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Final" }) };
    }
    mockStreamGroupDiscussion.mockReturnValue(paused());
    mockStreamGroupApproval.mockReturnValue(resumed());

    const { result } = renderHook(() => useGroupDiscussionStream());
    await act(async () => {
      await result.current.startStream("group-1", "Q");
    });
    expect(result.current.streamState.state).toBe("AWAITING_APPROVAL");
    const transcriptLenBefore = result.current.streamState.transcript.length;

    await act(async () => {
      await result.current.approveAndStream("group-1", "conv-hitl-3", { decision: { verdict: "APPROVED" } });
    });

    expect(mockStreamGroupApproval).toHaveBeenCalledWith(
      "group-1",
      "conv-hitl-3",
      { decision: { verdict: "APPROVED" } },
      expect.anything(),
    );
    expect(result.current.streamState.state).toBe("COMPLETED");
    expect(result.current.streamState.synthesizedAnswer).toBe("Final");
    expect(result.current.streamState.hitlResume).toEqual({ verdict: "APPROVED", note: undefined, decidedBy: "manager-user" });
    expect(result.current.streamState.hitlPause).toBeNull();
    // Transcript accrued before the pause is preserved across the resume.
    expect(result.current.streamState.transcript.length).toBeGreaterThanOrEqual(transcriptLenBefore);
  });

  it("promotes a member_pause_skipped turn to a SKIPPED entry carrying the reason", async () => {
    async function* mockEvents() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "c1", question: "Q" }) };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Opinion", phaseType: "OPINION" }) };
      yield { type: "speaker_start", data: JSON.stringify({ agentId: "a1", displayName: "Alpha", phaseIndex: 0, phaseName: "Opinion" }) };
      yield { type: "member_pause_skipped", data: JSON.stringify({ agentId: "a1", displayName: "Alpha", phaseIndex: 0, phaseName: "Opinion", reason: "Member paused — unsupported in a group" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "a1", displayName: "Alpha", phaseIndex: 0, response: null }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done" }) };
    }
    mockStreamGroupDiscussion.mockReturnValue(mockEvents());

    const { result } = renderHook(() => useGroupDiscussionStream());
    await act(async () => {
      await result.current.startStream("g", "Q");
    });

    const entry = result.current.streamState.transcript.find((e) => e.speakerAgentId === "a1");
    expect(entry?.type).toBe("SKIPPED");
    expect(entry?.errorReason).toBe("Member paused — unsupported in a group");
    expect(result.current.streamState.activeSpeakers.has("a1")).toBe(false);
  });

  it("surfaces an in-band 'group_error' event (approve/stream rejection) as FAILED", async () => {
    async function* paused() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "c1", question: "Q" }) };
      yield { type: "awaiting_approval", data: JSON.stringify({ phaseIndex: 0, phaseName: "P", reason: "r", granularity: "PHASE" }) };
    }
    // The approve/stream endpoint emits event name "group_error" for expected
    // rejections like a concurrent/duplicate decision (409) — EDDI issue #36
    // (a bare "error" would collide with the EventSource transport-error event).
    async function* rejected() {
      yield { type: "group_error", data: JSON.stringify({ error: "Concurrent modification" }) };
    }
    mockStreamGroupDiscussion.mockReturnValue(paused());
    mockStreamGroupApproval.mockReturnValue(rejected());

    const { result } = renderHook(() => useGroupDiscussionStream());
    await act(async () => {
      await result.current.startStream("g", "Q");
    });
    await act(async () => {
      await result.current.approveAndStream("g", "c1", { decision: { verdict: "APPROVED" } });
    });

    expect(result.current.streamState.state).toBe("FAILED");
    expect(result.current.streamState.error).toBe("Concurrent modification");
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.hitlResume).toBeNull();
  });

  // ── continueStream ──

  it("continueStream resets per-round derived fields while keeping transcript", async () => {
    // First: run a full discussion to populate state
    async function* firstRound() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-300", question: "Round 1" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({ tasks: [{ id: "t1", subject: "R", assignedTo: "A", priority: 0 }] }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Execute", phaseType: "EXECUTE" }) };
      yield { type: "speaker_start", data: JSON.stringify({ agentId: "a1", displayName: "A", phaseIndex: 0, phaseName: "Execute" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "a1", displayName: "A", phaseIndex: 0, response: "Done" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Round 1 answer" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(firstRound());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Round 1");
    });

    // Verify first round populated derived fields
    expect(result.current.streamState.synthesizedAnswer).toBe("Round 1 answer");
    expect(result.current.streamState.taskPlan).not.toBeNull();
    expect(result.current.streamState.tasksCompleted.has("t1")).toBe(true);

    // Second: continueStream — should reset derived fields
    async function* secondRound() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-300", question: "Round 2" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Round 2 answer" }) };
    }

    mockStreamGroupContinue.mockReturnValue(secondRound());

    await act(async () => {
      await result.current.continueStream("group-1", "conv-300", "Round 2");
    });

    // Derived fields should reflect round 2 only
    expect(result.current.streamState.synthesizedAnswer).toBe("Round 2 answer");
    expect(result.current.streamState.taskPlan).toBeNull();
    expect(result.current.streamState.tasksCompleted.size).toBe(0);
    expect(result.current.streamState.tasksInProgress.size).toBe(0);
    expect(result.current.streamState.taskVerifications.size).toBe(0);
    expect(result.current.streamState.hitlPause).toBeNull();
    expect(result.current.streamState.cancelInfo).toBeNull();
  });

  it("group_start appends the question on continuation (does not replace transcript)", async () => {
    // First round
    async function* firstRound() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-400", question: "First Q" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "a1", displayName: "Bot", phaseIndex: 0, response: "First A" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Synthesis 1" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(firstRound());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "First Q");
    });

    const transcriptAfterRound1 = result.current.streamState.transcript.length;
    expect(transcriptAfterRound1).toBeGreaterThanOrEqual(2); // user question + speaker

    // Continue — group_start should APPEND the new question, not replace
    async function* continuation() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-400", question: "Follow up Q" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "a1", displayName: "Bot", phaseIndex: 0, response: "Follow up A" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Synthesis 2" }) };
    }

    mockStreamGroupContinue.mockReturnValue(continuation());

    await act(async () => {
      await result.current.continueStream("group-1", "conv-400", "Follow up Q");
    });

    // Transcript should contain entries from BOTH rounds
    expect(result.current.streamState.transcript.length).toBeGreaterThan(transcriptAfterRound1);
    // First entry is still the original question
    expect(result.current.streamState.transcript[0]?.content).toBe("First Q");
    // New question was appended (not at index 0)
    const followUpEntry = result.current.streamState.transcript.find(
      (e) => e.content === "Follow up Q",
    );
    expect(followUpEntry).toBeDefined();
    expect(followUpEntry?.speakerAgentId).toBe("user");
  });

  it("group_start replaces transcript for a new discussion (no prior conversationId)", async () => {
    async function* events() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-500", question: "Brand new" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(events());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Brand new");
    });

    expect(result.current.streamState.transcript).toHaveLength(1);
    expect(result.current.streamState.transcript[0]?.content).toBe("Brand new");
  });

  // ── resetStream ──

  it("provides resetStream as a stable callback", () => {
    const { result, rerender } = renderHook(() => useGroupDiscussionStream());

    expect(typeof result.current.resetStream).toBe("function");

    const ref1 = result.current.resetStream;
    rerender();
    expect(result.current.resetStream).toBe(ref1);
  });

  it("resetStream clears all state back to initial", async () => {
    // Populate state via a full discussion
    async function* events() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-600", question: "Q" }) };
      yield {
        type: "task_plan_created",
        data: JSON.stringify({ tasks: [{ id: "t1", subject: "R", assignedTo: "A", priority: 0 }] }),
      };
      yield { type: "phase_start", data: JSON.stringify({ phaseIndex: 0, phaseName: "Verify", phaseType: "VERIFY" }) };
      yield { type: "task_verified", data: JSON.stringify({ taskId: "t1", passed: true, feedback: "OK" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done" }) };
    }

    mockStreamGroupDiscussion.mockReturnValue(events());

    const { result } = renderHook(() => useGroupDiscussionStream());

    await act(async () => {
      await result.current.startStream("group-1", "Q");
    });

    // Confirm state is populated
    expect(result.current.streamState.conversationId).toBe("conv-600");
    expect(result.current.streamState.synthesizedAnswer).toBe("Done");
    expect(result.current.streamState.taskPlan).not.toBeNull();
    expect(result.current.streamState.taskVerifications.size).toBe(1);

    // Reset
    act(() => {
      result.current.resetStream();
    });

    // Everything should be back to initial
    expect(result.current.streamState.isStreaming).toBe(false);
    expect(result.current.streamState.conversationId).toBeNull();
    expect(result.current.streamState.state).toBe("CREATED");
    expect(result.current.streamState.transcript).toEqual([]);
    expect(result.current.streamState.synthesizedAnswer).toBeNull();
    expect(result.current.streamState.currentPhase).toBeNull();
    expect(result.current.streamState.error).toBeNull();
    expect(result.current.streamState.taskPlan).toBeNull();
    expect(result.current.streamState.taskVerifications.size).toBe(0);
    expect(result.current.streamState.tasksInProgress.size).toBe(0);
    expect(result.current.streamState.tasksCompleted.size).toBe(0);
    expect(result.current.streamState.activeSpeakers.size).toBe(0);
    expect(result.current.streamState.hitlPause).toBeNull();
    expect(result.current.streamState.hitlResume).toBeNull();
    expect(result.current.streamState.cancelInfo).toBeNull();
    expect(result.current.streamState.startedAt).toBeNull();
  });

  it("provides continueStream as a stable callback", () => {
    const { result, rerender } = renderHook(() => useGroupDiscussionStream());

    expect(typeof result.current.continueStream).toBe("function");

    const ref1 = result.current.continueStream;
    rerender();
    expect(result.current.continueStream).toBe(ref1);
  });

  // ── Navigation resilience (store-backed state) ──

  it("keeps a group's stream state across unmount — a remounted board sees it", async () => {
    async function* events() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-nav", question: "Q" }) };
      yield { type: "speaker_complete", data: JSON.stringify({ agentId: "a1", displayName: "Bot", phaseIndex: 0, response: "A" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "Done" }) };
    }
    mockStreamGroupDiscussion.mockReturnValue(events());

    const first = renderHook(() => useGroupDiscussionStream("group-nav"));
    await act(async () => {
      await first.result.current.startStream("group-nav", "Q");
    });
    expect(first.result.current.streamState.transcript).toHaveLength(2);

    // Navigate away…
    first.unmount();

    // …and back: the board binds by groupId and picks the discussion up again.
    const second = renderHook(() => useGroupDiscussionStream("group-nav"));
    expect(second.result.current.streamState.conversationId).toBe("conv-nav");
    expect(second.result.current.streamState.transcript).toHaveLength(2);
    expect(second.result.current.streamState.synthesizedAnswer).toBe("Done");

    act(() => second.result.current.resetStream());
  });

  it("keeps each group's stream separate", async () => {
    async function* eventsA() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-a", question: "A?" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "A!" }) };
    }
    async function* eventsB() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-b", question: "B?" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "B!" }) };
    }
    mockStreamGroupDiscussion.mockReturnValueOnce(eventsA()).mockReturnValueOnce(eventsB());

    const a = renderHook(() => useGroupDiscussionStream("group-a"));
    const b = renderHook(() => useGroupDiscussionStream("group-b"));

    await act(async () => {
      await a.result.current.startStream("group-a", "A?");
    });
    await act(async () => {
      await b.result.current.startStream("group-b", "B?");
    });

    expect(a.result.current.streamState.conversationId).toBe("conv-a");
    expect(a.result.current.streamState.synthesizedAnswer).toBe("A!");
    expect(b.result.current.streamState.conversationId).toBe("conv-b");
    expect(b.result.current.streamState.synthesizedAnswer).toBe("B!");

    act(() => {
      a.result.current.resetStream();
      b.result.current.resetStream();
    });
  });

  it("a superseded stream cannot write over the discussion that replaced it", async () => {
    // First stream never terminates on its own; it is superseded mid-flight and
    // then fails — the classic "start a new discussion right away" sequence.
    let failFirst: ((e: Error) => void) | undefined;
    const firstBlocked = new Promise<void>((_resolve, reject) => {
      failFirst = reject;
    });
    async function* first() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-old", question: "Old" }) };
      await firstBlocked;
    }
    async function* second() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-new", question: "New" }) };
      yield { type: "group_complete", data: JSON.stringify({ synthesizedAnswer: "New answer" }) };
    }
    mockStreamGroupDiscussion.mockReturnValueOnce(first()).mockReturnValueOnce(second());

    const { result } = renderHook(() => useGroupDiscussionStream("group-race"));

    await act(async () => {
      result.current.startStream("group-race", "Old");
      await Promise.resolve();
    });
    expect(result.current.streamState.conversationId).toBe("conv-old");

    // Second discussion supersedes the first…
    await act(async () => {
      await result.current.startStream("group-race", "New");
    });
    expect(result.current.streamState.conversationId).toBe("conv-new");
    expect(result.current.streamState.state).toBe("COMPLETED");

    // …and the first one's late failure must not touch it.
    await act(async () => {
      failFirst?.(new Error("late failure from the abandoned stream"));
      await new Promise((r) => setTimeout(r, 0));
    });

    expect(result.current.streamState.state).toBe("COMPLETED");
    expect(result.current.streamState.error).toBeNull();
    expect(result.current.streamState.synthesizedAnswer).toBe("New answer");

    act(() => result.current.resetStream());
  });

  it("useStreamingGroupIds reports only groups whose stream is still live", async () => {
    // Never yields a terminal event, so the stream stays open.
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    async function* pending() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "conv-live", question: "Q" }) };
      await gate;
    }
    mockStreamGroupDiscussion.mockReturnValue(pending());

    const stream = renderHook(() => useGroupDiscussionStream("group-live"));
    const ids = renderHook(() => useStreamingGroupIds());

    expect(ids.result.current).toEqual([]);

    await act(async () => {
      stream.result.current.startStream("group-live", "Q");
      await Promise.resolve();
    });
    expect(ids.result.current).toEqual(["group-live"]);

    await act(async () => {
      release?.();
      await gate;
    });
    act(() => stream.result.current.abortStream());
    expect(ids.result.current).toEqual([]);

    act(() => stream.result.current.resetStream());
  });
});
