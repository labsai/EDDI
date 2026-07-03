import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useGroupDiscussionStream } from "@/hooks/use-group-discussion-stream";

const mockStreamGroupDiscussion = vi.fn();
const mockStreamGroupApproval = vi.fn();

vi.mock("@/lib/api/groups", async (importOriginal) => {
  const original = await importOriginal<typeof import("@/lib/api/groups")>();
  return {
    ...original,
    streamGroupDiscussion: (...args: unknown[]) => mockStreamGroupDiscussion(...args),
    streamGroupApproval: (...args: unknown[]) => mockStreamGroupApproval(...args),
  };
});

describe("useGroupDiscussionStream", () => {
  beforeEach(() => {
    mockStreamGroupDiscussion.mockReset();
    mockStreamGroupApproval.mockReset();
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

  it("surfaces an in-band 'error' event (approve/stream rejection) as FAILED", async () => {
    async function* paused() {
      yield { type: "group_start", data: JSON.stringify({ groupConversationId: "c1", question: "Q" }) };
      yield { type: "awaiting_approval", data: JSON.stringify({ phaseIndex: 0, phaseName: "P", reason: "r", granularity: "PHASE" }) };
    }
    // The approve/stream endpoint emits event name "error" (not "group_error")
    // for expected rejections like a concurrent/duplicate decision (409).
    async function* rejected() {
      yield { type: "error", data: JSON.stringify({ error: "Concurrent modification" }) };
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
});
