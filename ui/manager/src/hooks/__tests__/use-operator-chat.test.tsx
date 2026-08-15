import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import type { SSEEvent } from "@/lib/api/chat";
import type { SimpleConversationMemorySnapshot } from "@/lib/api/conversations";

/**
 * Drives the streaming and polling paths with fixtures each test controls via
 * the hoisted `h` state, mirroring the pattern in
 * `use-chat-sse-handling.test.tsx`.
 */
const h = vi.hoisted(() => ({
  frames: [] as Array<{ type: string; data: string }>,
  sendError: null as { status: number; message: string } | null,
  /** InputData bodies handed to sendMessageStreaming, in call order. */
  sentInputs: [] as Array<{ input: string; context?: Record<string, unknown> }>,
  conversationLogs: [] as Array<Partial<SimpleConversationMemorySnapshot>>,
  resumeCalls: [] as Array<{ conversationId: string; decision: unknown }>,
  /** Runs inside a conversation-log read, before it resolves — lets a test act
   *  while the read is genuinely in flight. */
  duringLogRead: null as null | (() => void),
  /** Runs after each yielded SSE frame — lets a test act mid-stream, e.g. to
   *  reset() a turn that is still being received. */
  duringStream: null as null | (() => void),
}));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    startConversation: vi.fn(async () => "conv-1"),
    sendMessageStreaming: async function* (
      _env: string,
      _agent: string,
      _conv: string,
      inputData: { input: string; context?: Record<string, unknown> },
    ) {
      h.sentInputs.push(inputData);
      if (h.sendError) throw h.sendError;
      for (const frame of h.frames) {
        yield frame as SSEEvent;
        h.duringStream?.();
      }
    },
  };
});

vi.mock("@/lib/api/conversations", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/conversations")>();
  return {
    ...actual,
    getSimpleConversationLog: vi.fn(async () => {
      const next = h.conversationLogs.shift();
      if (!next) throw new Error("test bug: ran out of mocked conversation logs");
      h.duringLogRead?.();
      return next as SimpleConversationMemorySnapshot;
    }),
  };
});

vi.mock("@/lib/api/hitl", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/hitl")>();
  return {
    ...actual,
    resumeConversation: vi.fn(async (conversationId: string, decision: unknown) => {
      h.resumeCalls.push({ conversationId, decision });
    }),
  };
});

import { useOperatorChat, useOperatorChatStore } from "../use-operator-chat";
import type { OperatorConfig } from "@/lib/api/operator";

function config(): OperatorConfig {
  return {
    enabled: true,
    agentId: "agent-1",
    version: 1,
    environment: "production",
    provider: "anthropic",
    model: "claude-sonnet-4-6",
    credentialKey: null,
    scope: "read_only",
    authMode: "caller-identity",
    promptBody: "Do the thing.",
  };
}

function textOutput(text: string) {
  return { output: [{ type: "text", text }] };
}

beforeEach(() => {
  h.frames = [];
  h.sendError = null;
  h.sentInputs = [];
  h.conversationLogs = [];
  h.resumeCalls = [];
  h.duringLogRead = null;
  h.duringStream = null;
  sessionStorage.clear();
  // The hook is now a thin wrapper around a module-level store, shared across
  // however many components mount it — including across these tests, which
  // used to get a fresh useState per renderHook call for free. reset() (the
  // real production action, not a raw setState) aborts any leftover in-flight
  // controllers and clears every field, same as a genuinely fresh mount would.
  useOperatorChatStore.getState().reset();
});

describe("pause detection from the streamed done event", () => {
  it("flags isPaused and backfills the placeholder from the pending message", async () => {
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          conversationOutputs: [textOutput("Waiting on a reviewer…")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("create an agent");
    });

    expect(result.current.isPaused).toBe(true);
    // Null from the snapshot ON PURPOSE: the simple snapshot never carries a
    // reason on the wire; the rendered reason overlays from approval-status.
    expect(result.current.pauseReason).toBeNull();
    const agentMessage = result.current.messages.find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("Waiting on a reviewer…");
  });

  it("does not flag a pause for an ordinary READY turn", async () => {
    h.frames = [
      { type: "token", data: "Hello" },
      { type: "done", data: JSON.stringify({ conversationState: "READY" }) },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.isPaused).toBe(false);
    expect(result.current.pauseReason).toBeNull();
  });

  it("tolerates a non-JSON done payload without throwing", async () => {
    h.frames = [{ type: "done", data: "not json" }];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });
    expect(result.current.isPaused).toBe(false);
    expect(result.current.error).toBeNull();
  });
});

describe("a send rejected 409 while already paused", () => {
  it("is treated as a pause, not an error, and drops the unsent optimistic bubbles", async () => {
    h.sendError = { status: 409, message: "Conflict" };

    const { result } = renderHook(() => useOperatorChat(config()));
    const messagesBefore = result.current.messages.length;
    await act(async () => {
      await result.current.send("are you still there?");
    });

    expect(result.current.isPaused).toBe(true);
    expect(result.current.error).toBeNull();
    // Neither the optimistic user message nor the empty agent placeholder
    // survive — the backend never received either.
    expect(result.current.messages.length).toBe(messagesBefore);
  });

  it("clears a stale resolveError, so the new card is not shown under an old failure", async () => {
    // A failed decision leaves resolveError set on purpose (the admin can try
    // again). But once a NEW pause arrives, that error describes a decision
    // nobody is still waiting on — the streamed pause path clears it, and this
    // one has to match or the banner reads as though the fresh card had failed.
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          hitlPausedAt: "2026-08-01T10:00:00Z",
          conversationOutputs: [textOutput("Pending…")],
        }),
      },
    ];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("do a thing");
    });

    const { resumeConversation } = await import("@/lib/api/hitl");
    vi.mocked(resumeConversation).mockRejectedValueOnce({ status: 500, message: "backend exploded" });
    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });
    expect(result.current.resolveError).toBeTruthy();

    h.sendError = { status: 409, message: "Conflict" };
    h.conversationLogs = [{ conversationState: "AWAITING_HUMAN" }];
    await act(async () => {
      await result.current.send("try again");
    });

    expect(result.current.isPaused).toBe(true);
    expect(result.current.resolveError).toBeNull();
  });

  it("still surfaces a non-409 error normally", async () => {
    h.sendError = { status: 500, message: "boom" };
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });
    expect(result.current.isPaused).toBe(false);
    expect(result.current.error).toContain("boom");
  });
});

describe("resolveApproval — reconciling the resumed turn", () => {
  /**
   * Pauses the hook via a streamed done event, returning its result handle.
   *
   * The snapshot carries exactly ONE conversationOutput, which is what the
   * backend actually sends: `/stream` defaults `returnCurrentStepOnly` to true
   * and the hook passes it explicitly on every `getSimpleConversationLog` call,
   * and `ConversationMemoryUtilities` collapses conversationOutputs to
   * `List.of(getLast())` in that mode. A fixture with two outputs at the TOP
   * level would be testing a response shape the API cannot produce (several
   * parts *within* the one output is a different thing, and is real).
   */
  async function pausedHook() {
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          hitlPausedAt: "2026-08-01T10:00:00Z",
          conversationOutputs: [textOutput("Waiting on a reviewer…")],
        }),
      },
    ];
    const rendered = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await rendered.result.current.send("do a thing");
    });
    expect(rendered.result.current.isPaused).toBe(true);
    return rendered;
  }

  it("reads ask → decision → answer, with the ask bubble kept", async () => {
    // The ask ("I need your approval…") must NOT be overwritten by the answer:
    // that put the decision rule above the very message it was answering,
    // reading as approval of a request that had not been made yet.
    const { result } = await pausedHook();
    h.conversationLogs = [
      { conversationState: "READY", conversationOutputs: [textOutput("Done — the agent was created.")] },
    ];

    await act(async () => {
      await result.current.resolveApproval("APPROVED", undefined, { "call-1": { verdict: "APPROVED" } });
    });

    expect(result.current.isPaused).toBe(false);
    expect(h.resumeCalls).toEqual([
      { conversationId: "conv-1", decision: { verdict: "APPROVED", note: undefined, toolDecisions: { "call-1": { verdict: "APPROVED" } } } },
    ]);
    expect(result.current.messages.map((m) => [m.role, m.content])).toEqual([
      ["user", "do a thing"],
      ["agent", "Waiting on a reviewer…"],
      ["system", "You approved this request."],
      ["agent", "Done — the agent was created."],
    ]);
  });

  it("keeps the ask bubble's message id, so its pipeline trace stays attached", async () => {
    const { result } = await pausedHook();
    const placeholderId = result.current.messages.find((m) => m.role === "agent")?.id;
    h.conversationLogs = [
      { conversationState: "READY", conversationOutputs: [textOutput("Done.")] },
    ];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    // The ask keeps its id — tracesByMessageId is keyed by it, and the trace
    // belongs to the very turn that paused.
    const askMessage = result.current.messages.find((m) => m.role === "agent");
    expect(askMessage?.id).toBe(placeholderId);
    expect(askMessage?.content).toBe("Waiting on a reviewer…");
  });

  it("stays paused when the resumed turn pauses AGAIN on a new batch", async () => {
    // The plan's own agent-creation flow is ~3 approval cards in a row, and the
    // backend permits maxPausesPerTurn (default 3). Waiting for the state to
    // clear would spin to the timeout on a conversation working as intended, so
    // a pause with a DIFFERENT hitlPausedAt counts as settled and becomes the
    // next card.
    const { result } = await pausedHook();
    h.conversationLogs = [
      {
        conversationState: "AWAITING_HUMAN",
        hitlPausedAt: "2026-08-01T10:05:00Z",
        conversationOutputs: [textOutput("Now waiting on batch two…")],
      },
    ];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    expect(result.current.isPaused).toBe(true);
    // Null from the snapshot ON PURPOSE: the simple snapshot never carries a
    // reason on the wire; the rendered reason overlays from approval-status.
    expect(result.current.pauseReason).toBeNull();
    expect(result.current.resolveError).toBeNull();
    expect(result.current.isResolvingPause).toBe(false);
    const agentMessages = result.current.messages.filter((m) => m.role === "agent");
    // The first ask stays; the new pending message follows the decision.
    expect(agentMessages.map((m) => m.content)).toEqual([
      "Waiting on a reviewer…",
      "Now waiting on batch two…",
    ]);
  });

  it("discards the resumed turn when the conversation was reset while polling", async () => {
    // `pollUntilSettled` can only see an abort between polls — the reads
    // themselves take no signal — so clearing the chat mid-read leaves this
    // continuation running against a conversation the user has thrown away.
    // Writing its answer into the emptied transcript would resurrect a
    // conversation that no longer exists, complete with its pause.
    const { result } = await pausedHook();
    h.conversationLogs = [
      { conversationState: "READY", conversationOutputs: [textOutput("Answer nobody is waiting for.")] },
    ];
    h.duringLogRead = () => result.current.reset();

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    expect(result.current.messages).toHaveLength(0);
    expect(result.current.isPaused).toBe(false);
    expect(result.current.conversationId).toBeNull();
  });

  it("tracks the LAST bubble of a multi-part re-pause as the next ask", async () => {
    // A pending message that renders as several bubbles still has exactly one
    // tail. Anchoring on its head would make the next decision read after the
    // opening line instead of after the ask it actually answers.
    const { result } = await pausedHook();
    h.conversationLogs = [
      {
        conversationState: "AWAITING_HUMAN",
        hitlPausedAt: "2026-08-01T10:05:00Z",
        conversationOutputs: [
          { output: [{ type: "text", text: "Part one." }, { type: "text", text: "Part two." }] },
        ],
      },
    ];
    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });
    // Conversation turns only — a decision also records a "system" entry now,
    // which is a transcript fact rather than something anyone said.
    expect(
      result.current.messages.filter((m) => m.role !== "system").map((m) => m.content),
    ).toEqual(["do a thing", "Waiting on a reviewer…", "Part one.", "Part two."]);

    h.conversationLogs = [
      { conversationState: "READY", conversationOutputs: [textOutput("Final answer.")] },
    ];
    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    // The second decision reads after "Part two." (the tail of the second ask),
    // and the final answer follows it — every earlier turn kept intact.
    expect(
      result.current.messages.filter((m) => m.role !== "system").map((m) => m.content),
    ).toEqual(["do a thing", "Waiting on a reviewer…", "Part one.", "Part two.", "Final answer."]);
    const systemIndexes = result.current.messages
      .map((m, i) => (m.role === "system" ? i : -1))
      .filter((i) => i >= 0);
    const secondDecisionIdx = systemIndexes[systemIndexes.length - 1]!;
    const partTwoIdx = result.current.messages.findIndex((m) => m.content === "Part two.");
    expect(secondDecisionIdx).toBe(partTwoIdx + 1);
  });

  it("renders every part when the resumed step emits several outputs", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [
      {
        conversationState: "READY",
        conversationOutputs: [{ output: [{ type: "text", text: "First." }, { type: "text", text: "Second." }] }],
      },
    ];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    const agentMessages = result.current.messages.filter((m) => m.role === "agent");
    expect(agentMessages.map((m) => m.content)).toEqual([
      "Waiting on a reviewer…",
      "First.",
      "Second.",
    ]);
  });

  it("APPENDS rather than replacing when the pause came from a 409 (no placeholder of ours)", async () => {
    // After a reload onto an already-paused conversation, the optimistic
    // bubbles were dropped — there is nothing to replace, so replacing "the
    // last agent message" would clobber an unrelated earlier answer.
    h.sendError = { status: 409, message: "Conflict" };
    h.conversationLogs = [{ conversationState: "AWAITING_HUMAN" }];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("are you still there?");
    });
    expect(result.current.isPaused).toBe(true);
    expect(result.current.messages).toHaveLength(0);

    h.conversationLogs = [
      { conversationState: "READY", conversationOutputs: [textOutput("Resumed and done.")] },
    ];
    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    const agentMessages = result.current.messages.filter((m) => m.role === "agent");
    expect(agentMessages).toHaveLength(1);
    expect(agentMessages[0]?.content).toBe("Resumed and done.");
  });

  it("reads the pause reason on a 409, so the banner is not blank", async () => {
    h.sendError = { status: 409, message: "Conflict" };
    h.conversationLogs = [
      { conversationState: "AWAITING_HUMAN" },
    ];
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });
    // Null from the snapshot ON PURPOSE: the simple snapshot never carries a
    // reason on the wire; the rendered reason overlays from approval-status.
    expect(result.current.pauseReason).toBeNull();
  });

  it("polls until the conversation leaves AWAITING_HUMAN rather than reading once", async () => {
    vi.useFakeTimers();
    try {
      const { result } = await pausedHook();
      // The SAME hitlPausedAt as the pause being decided — i.e. the decision has
      // not been acted on yet. A different one would mean a new card, not a
      // still-outstanding one, and would (correctly) stop the poll.
      h.conversationLogs = [
        { conversationState: "AWAITING_HUMAN", hitlPausedAt: "2026-08-01T10:00:00Z", conversationOutputs: [textOutput("pending #0")] },
        { conversationState: "AWAITING_HUMAN", hitlPausedAt: "2026-08-01T10:00:00Z", conversationOutputs: [textOutput("pending #0")] },
        { conversationState: "READY", conversationOutputs: [textOutput("Finally done.")] },
      ];

      await act(async () => {
        const resolvePromise = result.current.resolveApproval("APPROVED");
        // Two polls come back AWAITING_HUMAN before the loop sleeps past them.
        await vi.advanceTimersByTimeAsync(1_500);
        await vi.advanceTimersByTimeAsync(1_500);
        await resolvePromise;
      });

      expect(h.conversationLogs).toHaveLength(0); // all three were consumed
      expect(result.current.isPaused).toBe(false);
      // The ask stays; the answer lands after it (last agent bubble = the answer).
      const agents = result.current.messages.filter((m) => m.role === "agent");
      expect(agents[agents.length - 1]?.content).toBe("Finally done.");
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a timeout as resolveError without clearing the pause", async () => {
    vi.useFakeTimers();
    try {
      const { result } = await pausedHook();
      // Every poll still reports the SAME pause — the decision never lands.
      h.conversationLogs = Array.from({ length: 100 }, () => ({
        conversationState: "AWAITING_HUMAN" as const,
        hitlPausedAt: "2026-08-01T10:00:00Z",
        conversationOutputs: [textOutput("pending #0")],
      }));

      await act(async () => {
        const resolvePromise = result.current.resolveApproval("APPROVED");
        await vi.advanceTimersByTimeAsync(95_000);
        await resolvePromise;
      });

      expect(result.current.resolveError).toMatch(/timed out/i);
      // The admin can still decide again — the pause itself is not cleared out
      // from under them by a client-side timeout.
      expect(result.current.isPaused).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it("cannot distinguish a re-pause when the 409 pause carried no hitlPausedAt", async () => {
    // Pins a deliberate trade-off rather than asserting the ideal. With no
    // timestamp on the pause we decided, pollUntilSettled has nothing to
    // compare against and treats every AWAITING_HUMAN as that same pause — so
    // a genuine re-pause is polled through to the timeout instead of becoming
    // the next approval card. The alternative (treat any pause as new) would
    // clear the banner for a decision still outstanding, which is worse: it
    // loses a pending approval rather than delaying a visible one.
    vi.useFakeTimers();
    try {
      h.sendError = { status: 409, message: "Conflict" };
      // No hitlPausedAt — this is the shape that makes the branch reachable.
      h.conversationLogs = [{ conversationState: "AWAITING_HUMAN" }];
      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.send("still there?");
      });
      expect(result.current.isPaused).toBe(true);

      // The resumed turn genuinely pauses again, on a different batch.
      h.conversationLogs = Array.from({ length: 100 }, () => ({
        conversationState: "AWAITING_HUMAN" as const,
        hitlPausedAt: "2026-08-01T11:00:00Z",
        conversationOutputs: [textOutput("Batch two pending…")],
      }));

      await act(async () => {
        const resolvePromise = result.current.resolveApproval("APPROVED");
        await vi.advanceTimersByTimeAsync(95_000);
        await resolvePromise;
      });

      expect(result.current.resolveError).toMatch(/timed out/i);
      expect(result.current.isPaused).toBe(true);
      // The second batch's pending message never became a card.
      // Was pinned to the FIXTURE-served reason — a field the real wire
      // never carries. Null is the honest value on every snapshot path.
      expect(result.current.pauseReason).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports resumeConversation failing as resolveError, without polling", async () => {
    const { result } = await pausedHook();
    const { resumeConversation } = await import("@/lib/api/hitl");
    vi.mocked(resumeConversation).mockRejectedValueOnce({ status: 500, message: "backend exploded" });

    await act(async () => {
      await result.current.resolveApproval("REJECTED");
    });

    expect(result.current.resolveError).toContain("backend exploded");
    expect(result.current.isPaused).toBe(true);
  });

  it("does nothing when there is no conversation to resolve", async () => {
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });
    expect(h.resumeCalls).toEqual([]);
  });
});

describe("a turn orphaned by a mid-stream reset", () => {
  it("does not graft its trace onto the fresh conversation once it finally settles", async () => {
    // Two task events straddle a reset fired between them: the first is
    // recorded, wiped out by the reset, then the second re-accumulates into
    // `events` under the SAME (now stale, discarded) turn. The turn's own
    // `finally` must recognize the store has since moved on — via the shared
    // store's abortController, not a per-mount ref — and not write that
    // reaccumulated trace into the freshly-reset, unrelated state.
    h.frames = [
      { type: "task_start", data: JSON.stringify({ taskId: "t0", taskType: "httpcall" }) },
      { type: "task_start", data: JSON.stringify({ taskId: "t1", taskType: "httpcall" }) },
      { type: "done", data: JSON.stringify({ conversationState: "READY" }) },
    ];
    const { result } = renderHook(() => useOperatorChat(config()));
    let resetOnce = false;
    h.duringStream = () => {
      if (!resetOnce) {
        resetOnce = true;
        result.current.reset();
      }
    };

    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.conversationId).toBeNull();
    expect(result.current.messages).toEqual([]);
    expect(result.current.tracesByMessageId).toEqual({});
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * A turn can fail with NO stream-level error: the backend emits task_failed for
 * the failing step, streams zero tokens, and closes normally. That combination
 * used to leave an empty agent bubble and nothing else — the admin had to read
 * the server log to learn the turn failed at all (seen live when a provider
 * rejected the stored LLM config's temperature).
 */
describe("a failed turn that streams nothing", () => {
  it("surfaces the failing step and its summary as the chat error", async () => {
    h.frames = [
      {
        type: "task_failed",
        data: JSON.stringify({
          taskId: "t9",
          taskType: "ai.labs.langchain",
          index: 9,
          errorType: "unknown",
          errorSummary: "`temperature` is deprecated for this model.",
        }),
      },
      { type: "done", data: JSON.stringify({ conversationState: "READY" }) },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.error).toMatch(/langchain step failed/);
    expect(result.current.error).toMatch(/temperature/);
  });

  it("points at the server log when the failure carries no summary", async () => {
    h.frames = [
      {
        type: "task_failed",
        data: JSON.stringify({ taskId: "t9", taskType: "ai.labs.langchain", index: 9 }),
      },
      { type: "done", data: JSON.stringify({ conversationState: "READY" }) },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.error).toMatch(/server log has the full error/i);
  });

  it("stays quiet when a step failed but the turn still answered", async () => {
    // A recovered turn (retry, fallback content) must not append a scary error
    // to a visible answer.
    h.frames = [
      {
        type: "task_failed",
        data: JSON.stringify({ taskId: "t2", taskType: "ai.labs.httpcalls", index: 2 }),
      },
      { type: "token", data: "Here is your answer." },
      { type: "done", data: JSON.stringify({ conversationState: "READY" }) },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.error).toBeNull();
  });

  it("does not double-report when the turn paused instead of failing", async () => {
    h.frames = [
      {
        type: "task_failed",
        data: JSON.stringify({ taskId: "t2", taskType: "ai.labs.httpcalls", index: 2 }),
      },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          conversationOutputs: [textOutput("Waiting…")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("do a write");
    });

    expect(result.current.isPaused).toBe(true);
    expect(result.current.error).toBeNull();
  });
});

/**
 * CodeRabbit (PR #143): a turn can answer entirely through the done snapshot —
 * zero token frames — and an earlier recoverable task_failed must not overwrite
 * that answer with an error banner.
 */
describe("a turn that answers via the done snapshot despite an earlier task_failed", () => {
  it("backfills the answer and raises no error", async () => {
    h.frames = [
      {
        type: "task_failed",
        data: JSON.stringify({ taskId: "t2", taskType: "ai.labs.httpcalls", index: 2 }),
      },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [textOutput("Recovered — here is the answer.")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.error).toBeNull();
    const agentMessage = result.current.messages.find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("Recovered — here is the answer.");
  });

  it("still reports the failure when the snapshot carries no output either", async () => {
    h.frames = [
      {
        type: "task_failed",
        data: JSON.stringify({ taskId: "t2", taskType: "ai.labs.langchain", index: 2 }),
      },
      { type: "done", data: JSON.stringify({ conversationState: "READY", conversationOutputs: [] }) },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    expect(result.current.error).toMatch(/langchain step failed/);
  });
});

describe("attachments on a turn", () => {
  it("merges attachment_* refs into the turn context and shows chips on the user bubble", async () => {
    h.frames = [{ type: "done", data: JSON.stringify({ conversationState: "READY" }) }];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("look at this", undefined, [
        {
          storageRef: "ref-9",
          fileName: "shot.png",
          mimeType: "image/png",
          sizeBytes: 4,
          forwardableInline: true,
          previewUrl: "blob:preview",
        },
      ]);
    });

    // The backend contract: attachment_N context entries carrying the ref.
    expect(h.sentInputs[0]?.context?.attachment_0).toEqual({
      type: "object",
      value: { storageRef: "ref-9", fileName: "shot.png" },
    });
    // The sent bubble carries the display chips.
    const userMessage = result.current.messages.find((m) => m.role === "user");
    expect(userMessage?.attachments?.[0]?.fileName).toBe("shot.png");
    expect(userMessage?.attachments?.[0]?.previewUrl).toBe("blob:preview");
  });

  it("allows an attachment-only turn (no text)", async () => {
    h.frames = [{ type: "done", data: JSON.stringify({ conversationState: "READY" }) }];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("", undefined, [
        { storageRef: "ref-1", fileName: "doc.pdf", mimeType: "application/pdf", sizeBytes: 10 },
      ]);
    });

    expect(h.sentInputs).toHaveLength(1);
    expect(h.sentInputs[0]?.context?.attachment_0).toBeDefined();
  });

  it("still refuses a turn with neither text nor attachments", async () => {
    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("", undefined, []);
    });
    expect(h.sentInputs).toHaveLength(0);
  });

  it("ensureConversation creates the conversation once and then reuses it", async () => {
    const { result } = renderHook(() => useOperatorChat(config()));

    let first = "";
    let second = "";
    await act(async () => {
      first = await result.current.ensureConversation();
      second = await result.current.ensureConversation();
    });

    expect(first).toBe("conv-1");
    expect(second).toBe("conv-1");
    // send() must then REUSE the lazily-created conversation, not start another.
    h.frames = [{ type: "done", data: JSON.stringify({ conversationState: "READY" }) }];
    await act(async () => {
      await result.current.send("hi");
    });
    const { startConversation } = await import("@/lib/api/chat");
    expect(vi.mocked(startConversation)).toHaveBeenCalledTimes(1);
  });
});

describe("streamed interim text vs the canonical answer", () => {
  it("snaps the bubble to the done snapshot's text when rounds streamed interim commentary", async () => {
    // Tool-enabled turns stream every model round: "Let me check…" (interim,
    // discarded from memory) then the final answer. The resting bubble must
    // equal what a reload would show — the snapshot text alone.
    h.frames = [
      { type: "token", data: "Let me check the agents… " },
      { type: "token", data: "There are 3 agents deployed." },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [textOutput("There are 3 agents deployed.")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("how many agents?");
    });

    const agentMessage = result.current.messages.find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("There are 3 agents deployed.");
  });

  it("leaves the bubble alone when the streamed text already equals the snapshot", async () => {
    h.frames = [
      { type: "token", data: "Same answer" },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [textOutput("Same answer")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("hi");
    });

    const agentMessage = result.current.messages.find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("Same answer");
  });

  it("a paused turn's bubble rests on the pending message even after interim streaming", async () => {
    h.frames = [
      { type: "token", data: "I will rename the agent now… " },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          conversationOutputs: [textOutput("Waiting for your approval to rename the agent.")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("rename it");
    });

    const agentMessage = result.current.messages.find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("Waiting for your approval to rename the agent.");
    expect(result.current.isPaused).toBe(true);
  });
});

describe("attachment preview lifecycle in the store", () => {
  it("reset() revokes sent-bubble preview URLs before dropping the messages", async () => {
    const revoked: string[] = [];
    const original = URL.revokeObjectURL;
    URL.revokeObjectURL = (url: string) => revoked.push(url);
    try {
      h.frames = [{ type: "done", data: JSON.stringify({ conversationState: "READY" }) }];
      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.send("here", undefined, [
          { storageRef: "r1", fileName: "img.png", mimeType: "image/png", sizeBytes: 3, previewUrl: "blob:img-1" },
        ]);
      });

      await act(async () => {
        result.current.reset();
      });

      expect(revoked).toContain("blob:img-1");
    } finally {
      URL.revokeObjectURL = original;
    }
  });

  it("a 409-refused send revokes the dropped optimistic bubble's previews", async () => {
    const revoked: string[] = [];
    const original = URL.revokeObjectURL;
    URL.revokeObjectURL = (url: string) => revoked.push(url);
    try {
      h.sendError = { status: 409, message: "Conflict" };
      // The 409 path reads the pause reason afterwards.
      h.conversationLogs = [{ conversationState: "AWAITING_HUMAN" }];
      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.send("try", undefined, [
          { storageRef: "r2", fileName: "shot.png", mimeType: "image/png", sizeBytes: 3, previewUrl: "blob:img-2" },
        ]);
      });

      expect(revoked).toContain("blob:img-2");
    } finally {
      URL.revokeObjectURL = original;
    }
  });

  it("a send refused while another surface streams revokes the drained previews", async () => {
    const revoked: string[] = [];
    const original = URL.revokeObjectURL;
    URL.revokeObjectURL = (url: string) => revoked.push(url);
    try {
      useOperatorChatStore.setState({ isStreaming: true });
      const { result } = renderHook(() => useOperatorChat(config()));
      await act(async () => {
        await result.current.send("busy", undefined, [
          { storageRef: "r3", fileName: "doc.pdf", mimeType: "application/pdf", sizeBytes: 3, previewUrl: "blob:img-3" },
        ]);
      });

      expect(h.sentInputs).toHaveLength(0);
      expect(revoked).toContain("blob:img-3");
    } finally {
      URL.revokeObjectURL = original;
      useOperatorChatStore.setState({ isStreaming: false });
    }
  });
});

describe("ensureConversation concurrency", () => {
  it("two concurrent calls share one create — the second must not orphan the first gesture's upload", async () => {
    const { startConversation } = await import("@/lib/api/chat");
    vi.mocked(startConversation).mockClear();

    const { result } = renderHook(() => useOperatorChat(config()));
    let first = "";
    let second = "";
    await act(async () => {
      const [a, b] = await Promise.all([
        result.current.ensureConversation(),
        result.current.ensureConversation(),
      ]);
      first = a;
      second = b;
    });

    expect(first).toBe("conv-1");
    expect(second).toBe("conv-1");
    expect(vi.mocked(startConversation)).toHaveBeenCalledTimes(1);
  });

  it("a reset() during the in-flight create does not resurrect the conversation", async () => {
    const { startConversation } = await import("@/lib/api/chat");
    vi.mocked(startConversation).mockClear();
    let release: (id: string) => void = () => {};
    vi.mocked(startConversation).mockImplementationOnce(
      () => new Promise<string>((resolve) => { release = resolve; }),
    );

    const { result } = renderHook(() => useOperatorChat(config()));
    let created: Promise<string>;
    act(() => {
      created = result.current.ensureConversation();
    });
    await act(async () => {
      result.current.reset();
      release("conv-late");
      await created!;
    });

    expect(useOperatorChatStore.getState().conversationId).toBeNull();
  });
});

/**
 * A decision must ALWAYS produce a visible continuation.
 *
 * Observed: the approver clicked Approve, the resumed turn came back with no
 * output because it had paused again on the same tool, and NOTHING was added to
 * the transcript — approving looked identical to nothing happening. The decision
 * itself, and the reason there is no answer, are now recorded either way.
 */
describe("resolveApproval — every decision leaves a trace", () => {
  async function pausedHook() {
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          hitlPausedAt: "2026-08-01T10:00:00Z",
          conversationOutputs: [textOutput("Waiting on a reviewer…")],
        }),
      },
    ];
    const rendered = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await rendered.result.current.send("do a thing");
    });
    expect(rendered.result.current.isPaused).toBe(true);
    return rendered;
  }

  it("records the approval even when the resumed turn returns no text", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [{ conversationState: "READY", conversationOutputs: [] }];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    const system = result.current.messages.filter((m) => m.role === "system");
    expect(system.map((m) => m.code)).toEqual(["approved", "noReply"]);
  });

  it("says the turn paused again rather than going silent", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [
      {
        conversationState: "AWAITING_HUMAN",
        hitlPausedAt: "2026-08-01T10:05:00Z",
        conversationOutputs: [],
      },
    ];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    const codes = result.current.messages.filter((m) => m.role === "system").map((m) => m.code);
    expect(codes).toEqual(["approved", "rePaused"]);
    expect(result.current.isPaused).toBe(true);
  });

  it("records a rejection too", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [{ conversationState: "READY", conversationOutputs: [textOutput("Stopped.")] }];

    await act(async () => {
      await result.current.resolveApproval("REJECTED");
    });

    const system = result.current.messages.filter((m) => m.role === "system");
    expect(system).toHaveLength(1);
    expect(system[0]!.code).toBe("rejected");
  });

  // The banner submits "approve these, not that one" as a top-level APPROVED
  // carrying per-call REJECTEDs. Recording that as a flat "approved" would put a
  // claim in the permanent transcript the approver never made.
  it("calls a partly-rejected batch partial, not approved", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [{ conversationState: "READY", conversationOutputs: [textOutput("Did the rest.")] }];

    await act(async () => {
      await result.current.resolveApproval("APPROVED", undefined, {
        "call-1": { verdict: "APPROVED" },
        "call-2": { verdict: "REJECTED" },
        "call-3": { verdict: "REJECTED" },
      });
    });

    const system = result.current.messages.filter((m) => m.role === "system");
    expect(system).toHaveLength(1);
    expect(system[0]!.code).toBe("partial");
    expect(system[0]!.count).toBe(2);
  });

  it("stays 'approved' when the per-call map only amends, rejecting nothing", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [{ conversationState: "READY", conversationOutputs: [textOutput("Done.")] }];

    await act(async () => {
      await result.current.resolveApproval("APPROVED", undefined, {
        "call-1": { verdict: "APPROVED", amendedArguments: '{"n":1}' },
      });
    });

    const system = result.current.messages.filter((m) => m.role === "system");
    expect(system[0]!.code).toBe("approved");
    // No count on a non-partial entry: it is the plural argument for exactly one key.
    expect(system[0]!.count).toBeUndefined();
  });

  it("adds no outcome notice when there IS an answer — the answer is the continuation", async () => {
    const { result } = await pausedHook();
    h.conversationLogs = [{ conversationState: "READY", conversationOutputs: [textOutput("Created it.")] }];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    const codes = result.current.messages.filter((m) => m.role === "system").map((m) => m.code);
    expect(codes).toEqual(["approved"]);
    expect(result.current.messages.some((m) => m.content === "Created it.")).toBe(true);
  });
});
