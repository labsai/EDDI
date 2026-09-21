import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { type SSEEvent } from "@/lib/api/chat";

// Drive the streaming path with an arbitrary set of SSE frames per test. The
// mock reads from a hoisted array so each test can queue up the frames it wants
// pushed through handleSSEEvent (task_failed / error / …).
const h = vi.hoisted(() => ({ frames: [] as Array<{ type: string; data: string }> }));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* () {
      for (const frame of h.frames) {
        yield frame as SSEEvent;
      }
    },
  };
});

import { useChatStore, useSendMessage } from "@/hooks/use-chat";
import { useDebugStore } from "@/hooks/use-debug-events";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("use-chat handleSSEEvent — task_failed / error-JSON handling", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    useChatStore.setState({
      selectedAgentId: "agent1",
      conversationId: "conv1",
      streamingEnabled: true,
    });
  });

  it("dispatches a task_failed debug event with the classified errorType/summary and stops thinking", async () => {
    // A task_start lights up the "thinking" indicator; the following task_failed
    // must classify the failure into a debug event AND turn thinking back off.
    h.frames = [
      {
        type: "task_start",
        data: JSON.stringify({ taskId: "task-42", taskType: "ai.labs.llm", index: 2 }),
      },
      {
        type: "task_failed",
        data: JSON.stringify({
          taskId: "task-42",
          taskType: "ai.labs.llm",
          index: 2,
          durationMs: 1200,
          errorType: "timeout",
          error: "Request timed out",
        }),
      },
    ];

    const { result } = renderHook(() => useSendMessage(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ message: "hi" });
    });

    // The stream ended without a done frame, so the safety net finalizes the
    // debug turn (turn-boundary fix) — the events live in the turn history
    // now, not the live set.
    const finalized = useDebugStore.getState().turns;
    expect(finalized).toHaveLength(1);
    const events = finalized[0]!.events;
    const failed = events.find((e) => e.type === "task_failed");
    expect(failed).toBeDefined();
    expect(failed?.taskId).toBe("task-42");
    expect(failed?.errorType).toBe("timeout");
    expect(failed?.errorSummary).toBe("Request timed out");
    expect(failed?.durationMs).toBe(1200);

    // setThinking(false) ran on the failure
    expect(useChatStore.getState().isThinking).toBe(false);
  });

  it("renders the parsed .message from an error frame, not the raw JSON payload", async () => {
    h.frames = [{ type: "error", data: JSON.stringify({ message: "Boom" }) }];

    const { result } = renderHook(() => useSendMessage(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ message: "hi" });
    });

    const messages = useChatStore.getState().messages;
    const lastAgent = [...messages].reverse().find((m) => m.role === "agent");
    expect(lastAgent?.content).toContain("⚠️ Error: Boom");
    // The raw JSON object must not leak into the bubble.
    expect(lastAgent?.content).not.toContain('{"message"');
  });
});

describe("use-chat handleSSEEvent — canonical snapshot text on done", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    useChatStore.setState({
      selectedAgentId: "agent1",
      conversationId: "conv1",
      streamingEnabled: true,
    });
  });

  it("snaps the streamed bubble to the done snapshot's text when interim rounds streamed", async () => {
    // Tool-enabled turns stream every model round live — interim commentary
    // precedes the final answer in the bubble, but the stored transcript keeps
    // only the final answer. At rest the bubble must equal a reload.
    h.frames = [
      { type: "token", data: "Let me look that up… " },
      { type: "token", data: "The deployment is healthy." },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: "The deployment is healthy." }] }],
        }),
      },
    ];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "status?" });
    });

    const messages = useChatStore.getState().messages;
    const agentMessage = [...messages].reverse().find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("The deployment is healthy.");
  });

  it("still back-fills an empty bubble from the snapshot (structured-JSON turns)", async () => {
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: "Structured answer" }] }],
        }),
      },
    ];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "go" });
    });

    const messages = useChatStore.getState().messages;
    const agentMessage = [...messages].reverse().find((m) => m.role === "agent");
    expect(agentMessage?.content).toBe("Structured answer");
  });
});

describe("use-chat — turn boundary on abnormal stream endings", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    useChatStore.setState({
      selectedAgentId: "agent1",
      conversationId: "conv1",
      streamingEnabled: true,
    });
  });

  it("a turn that ends without a done frame does not leak its events into the next turn", async () => {
    // Turn 1: a task event arrives, then the stream just closes — no done, no
    // error frame (connection drop). Without the boundary fix the events stay
    // in currentTurnEvents and the NEXT turn's live status line opens showing
    // this turn's tools.
    h.frames = [
      {
        type: "task_complete",
        data: JSON.stringify({
          taskId: "t1",
          taskType: "ai.labs.httpcalls",
          index: 0,
          durationMs: 3,
          toolTrace: [{ type: "tool_call", tool: "leakedTool", arguments: "{}" }],
        }),
      },
    ];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "first" });
    });

    expect(useDebugStore.getState().currentTurnEvents).toHaveLength(0);

    // Turn 2 starts: the live set must be empty before its own events arrive.
    h.frames = [{ type: "done", data: JSON.stringify({ conversationState: "READY" }) }];
    await act(async () => {
      await result.current.mutateAsync({ message: "second" });
    });
    expect(
      useDebugStore.getState().currentTurnEvents.some(
        (e) => e.toolTrace?.some((tr) => tr.tool === "leakedTool"),
      ),
    ).toBe(false);
  });
});
