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
  conversationLogs: [] as Array<Partial<SimpleConversationMemorySnapshot>>,
  resumeCalls: [] as Array<{ conversationId: string; decision: unknown }>,
}));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    startConversation: vi.fn(async () => "conv-1"),
    sendMessageStreaming: async function* () {
      if (h.sendError) throw h.sendError;
      for (const frame of h.frames) yield frame as SSEEvent;
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

import { useOperatorChat } from "../use-operator-chat";
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
  h.conversationLogs = [];
  h.resumeCalls = [];
  sessionStorage.clear();
});

describe("pause detection from the streamed done event", () => {
  it("flags isPaused and backfills the placeholder from the pending message", async () => {
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          hitlPauseReason: "Creating a new agent — review the whole config",
          conversationOutputs: [textOutput("Waiting on a reviewer…")],
        }),
      },
    ];

    const { result } = renderHook(() => useOperatorChat(config()));
    await act(async () => {
      await result.current.send("create an agent");
    });

    expect(result.current.isPaused).toBe(true);
    expect(result.current.pauseReason).toBe("Creating a new agent — review the whole config");
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
  /** Pauses the hook via a streamed done event, returning its result handle. */
  async function pausedHook(pausedOutputCount = 1) {
    const outputs = Array.from({ length: pausedOutputCount }, (_, i) => textOutput(`pending #${i}`));
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "AWAITING_HUMAN",
          hitlPauseReason: "Approval required",
          conversationOutputs: outputs,
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

  it("replaces the placeholder in place when the resumed turn reuses the SAME step (TOOL_CALL resume)", async () => {
    // conversationOutputs.length stays 1 — the backend appended the final
    // answer to the step it paused in, exactly as LlmTask.executeResume does.
    const { result } = await pausedHook(1);
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
    const agentMessages = result.current.messages.filter((m) => m.role === "agent");
    // No duplicate: the ONE agent bubble now holds the final answer.
    expect(agentMessages).toHaveLength(1);
    expect(agentMessages[0]?.content).toBe("Done — the agent was created.");
  });

  it("appends a new bubble when the resumed turn commits as a NEW step (RULE resume advancing)", async () => {
    const { result } = await pausedHook(1);
    h.conversationLogs = [
      {
        conversationState: "READY",
        conversationOutputs: [textOutput("pending #0"), textOutput("The rule pause resolved; continuing.")],
      },
    ];

    await act(async () => {
      await result.current.resolveApproval("APPROVED");
    });

    expect(result.current.isPaused).toBe(false);
    const agentMessages = result.current.messages.filter((m) => m.role === "agent");
    // The original placeholder bubble is untouched AND a new one is added —
    // nothing is silently overwritten.
    expect(agentMessages).toHaveLength(2);
    expect(agentMessages[0]?.content).toBe("pending #0");
    expect(agentMessages[1]?.content).toBe("The rule pause resolved; continuing.");
  });

  it("polls until the conversation leaves AWAITING_HUMAN rather than reading once", async () => {
    vi.useFakeTimers();
    try {
      const { result } = await pausedHook(1);
      h.conversationLogs = [
        { conversationState: "AWAITING_HUMAN", conversationOutputs: [textOutput("pending #0")] },
        { conversationState: "AWAITING_HUMAN", conversationOutputs: [textOutput("pending #0")] },
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
      expect(result.current.messages.find((m) => m.role === "agent")?.content).toBe("Finally done.");
    } finally {
      vi.useRealTimers();
    }
  });

  it("reports a timeout as resolveError without clearing the pause", async () => {
    vi.useFakeTimers();
    try {
      const { result } = await pausedHook(1);
      // Every poll still reports AWAITING_HUMAN — never settles.
      h.conversationLogs = Array.from({ length: 100 }, () => ({
        conversationState: "AWAITING_HUMAN" as const,
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

  it("reports resumeConversation failing as resolveError, without polling", async () => {
    const { result } = await pausedHook(1);
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

afterEach(() => {
  vi.restoreAllMocks();
});
