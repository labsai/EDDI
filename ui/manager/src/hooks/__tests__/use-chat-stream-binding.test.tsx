import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { type SSEEvent } from "@/lib/api/chat";
import { readFileSync } from "node:fs";

/**
 * A send belongs to the transcript it started in.
 *
 * The streaming mock yields its frames one at a time and can stop at a gate the
 * test opens, so a test can change what the store holds while a stream is
 * still delivering — which is exactly when the old code wrote conversation A's
 * tokens into conversation B's last bubble.
 */
const h = vi.hoisted(() => ({
  frames: [] as Array<{ type: string; data: string }>,
  /** Index before which the stream waits for `release()`. -1 = never waits. */
  gateAt: -1,
  release: (() => {}) as () => void,
  reachedGate: (() => {}) as () => void,
  /** Whether the stream's abort signal fired. */
  aborted: false,
  toastError: vi.fn(),
}));

vi.mock("sonner", () => ({ toast: { error: h.toastError, success: vi.fn() } }));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* (
      _env: string,
      _agentId: string,
      _conversationId: string,
      _input: unknown,
      signal?: AbortSignal,
    ) {
      signal?.addEventListener("abort", () => (h.aborted = true));
      for (let i = 0; i < h.frames.length; i++) {
        if (i === h.gateAt) {
          // Like a real fetch body, a pending read ends with an AbortError
          // when the signal fires.
          await new Promise<void>((resolve, reject) => {
            h.release = resolve;
            signal?.addEventListener("abort", () =>
              reject(new DOMException("aborted", "AbortError")),
            );
            h.reachedGate();
          });
        }
        yield h.frames[i] as SSEEvent;
      }
    },
  };
});

import {
  DETACHED_STREAM_GRACE_MS,
  UNCONSUMED_STREAM_ERROR_CODES,
  useChatStore,
  useSendMessage,
} from "@/hooks/use-chat";
import { useDebugStore } from "@/hooks/use-debug-events";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function doneFrame(output: Record<string, unknown>, state = "READY") {
  return {
    type: "done",
    data: JSON.stringify({ conversationState: state, conversationOutputs: [output] }),
  };
}

describe("useSendMessage — a stream stays bound to its own transcript", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    h.gateAt = -1;
    h.aborted = false;
    h.toastError.mockReset();
    useChatStore.setState({
      selectedAgentId: "agent-a",
      conversationId: "conv-a",
      streamingEnabled: true,
    });
  });

  it("writes nothing into another agent's conversation opened mid-stream", async () => {
    h.frames = [
      { type: "token", data: "Hello from A" },
      { type: "token", data: " — still A" },
      doneFrame({ output: [{ type: "text", text: "Hello from A — still A" }], quickReplies: ["A-only"] }),
    ];
    h.gateAt = 1;
    const gateReached = new Promise<void>((r) => (h.reachedGate = r));

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    let sending!: Promise<unknown>;
    act(() => {
      sending = result.current.mutateAsync({ message: "hi A" });
    });
    await gateReached;
    const shown = useChatStore.getState().messages;
    expect(shown[shown.length - 1]?.content).toBe("Hello from A");

    // The user switches to agent B and its conversation loads.
    act(() => {
      const chat = useChatStore.getState();
      chat.setSelectedAgent("agent-b", "B");
      chat.setConversationId("conv-b");
      chat.addMessage({ id: "b-1", role: "agent", content: "B's own reply", timestamp: 1 });
    });
    // Switching must not leave B's input locked behind A's send.
    expect(useChatStore.getState().isProcessing).toBe(false);

    await act(async () => {
      h.release();
      await sending;
    });

    const state = useChatStore.getState();
    expect(state.messages.map((m) => m.content)).toEqual(["B's own reply"]);
    expect(state.quickReplies).toEqual([]);
    expect(state.isProcessing).toBe(false);
  });

  it("drops a non-streaming reply that arrives after the conversation was replaced", async () => {
    useChatStore.setState({ streamingEnabled: false });
    let releaseReply!: () => void;
    const replyGate = new Promise<void>((r) => (releaseReply = r));
    server.use(
      http.post("*/agents/:conversationId", async () => {
        await replyGate;
        return HttpResponse.json({
          conversationState: "READY",
          conversationSteps: [],
          conversationOutputs: [{ output: [{ type: "text", text: "late reply for A" }] }],
        });
      }),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    let sending!: Promise<unknown>;
    act(() => {
      sending = result.current.mutateAsync({ message: "hi A" });
    });
    await waitFor(() => expect(useChatStore.getState().isProcessing).toBe(true));

    act(() => {
      useChatStore.getState().clearMessages();
      useChatStore.getState().setConversationId("conv-new");
    });

    await act(async () => {
      releaseReply();
      await sending;
    });

    expect(useChatStore.getState().messages).toEqual([]);
  });

  it("keeps an error from a detached send out of the transcript now on screen", async () => {
    useChatStore.setState({ streamingEnabled: false });
    let releaseReply!: () => void;
    const replyGate = new Promise<void>((r) => (releaseReply = r));
    server.use(
      http.post("*/agents/:conversationId", async () => {
        await replyGate;
        return new HttpResponse("boom", { status: 500 });
      }),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    act(() => {
      result.current.mutate({ message: "hi A" });
    });
    await waitFor(() => expect(useChatStore.getState().isProcessing).toBe(true));

    act(() => {
      useChatStore.getState().setSelectedAgent("agent-b", "B");
      useChatStore.getState().setConversationId("conv-b");
    });

    releaseReply();
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(useChatStore.getState().messages).toEqual([]);
  });

  it("treats an awaiting_approval stream frame as an unconsumed send, not an error", async () => {
    h.frames = [
      {
        type: "error",
        data: JSON.stringify({
          code: "awaiting_approval",
          message: "Conversation is awaiting human approval",
        }),
      },
    ];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "are you there?" }).catch(() => {});
    });

    const state = useChatStore.getState();
    // Neither the optimistic user message nor an error blob stays behind.
    expect(state.messages).toEqual([]);
    expect(state.isPaused).toBe(true);
    expect(state.isProcessing).toBe(false);
  });

  it("honours an input field requested by a streamed reply", async () => {
    h.frames = [
      doneFrame({
        output: [
          { type: "text", text: "Paste your API key" },
          { type: "inputField", subType: "password", label: "API Key" },
        ],
      }),
    ];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "set me up" });
    });

    expect(useChatStore.getState().activeInputField).toEqual(
      expect.objectContaining({ subType: "password", label: "API Key" }),
    );
  });

  it("clears a requested input field once the next turn is sent", async () => {
    useChatStore.getState().setInputField({ subType: "password" });
    h.frames = [doneFrame({ output: [{ type: "text", text: "Thanks" }] })];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "sk-123", isSecret: true });
    });

    expect(useChatStore.getState().activeInputField).toBeNull();
  });
});

describe("setSelectedAgent — nothing of the previous agent's conversation survives", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
  });

  it("drops quick replies, a requested input field and a pending send lock", () => {
    useChatStore.setState({
      selectedAgentId: "agent-a",
      conversationId: "conv-a",
      quickReplies: ["A-only"],
      activeInputField: { subType: "password" },
      isProcessing: true,
      isThinking: true,
    });
    const epoch = useChatStore.getState().conversationEpoch;

    useChatStore.getState().setSelectedAgent("agent-b", "B");

    const state = useChatStore.getState();
    expect(state.quickReplies).toEqual([]);
    expect(state.activeInputField).toBeNull();
    expect(state.isProcessing).toBe(false);
    expect(state.isThinking).toBe(false);
    expect(state.conversationEpoch).toBe(epoch + 1);
  });
});

describe("useSendMessage — streamed refusals and detached streams", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    h.gateAt = -1;
    h.aborted = false;
    h.toastError.mockReset();
    useChatStore.setState({
      selectedAgentId: "agent-a",
      conversationId: "conv-a",
      streamingEnabled: true,
    });
  });

  // The streaming endpoint reports its pre-turn refusals as error frames, not
  // statuses. Only awaiting_approval used to be rolled back; "agent version
  // mismatch" left the unsent message in the transcript above an error bubble.
  it("rolls back a refusal that is not a pause and says why, without the pause banner", async () => {
    h.frames = [
      { type: "error", data: JSON.stringify({ code: "agent_mismatch", message: "Agent version mismatch" }) },
    ];
    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "hello" }).catch(() => {});
    });

    const state = useChatStore.getState();
    expect(state.messages).toEqual([]);
    expect(state.isPaused).toBe(false);
    expect(state.isProcessing).toBe(false);
    expect(h.toastError).toHaveBeenCalledTimes(1);
  });

  it("keeps the error bubble for a failure during the turn (no code: the turn ran)", async () => {
    h.frames = [
      { type: "token", data: "partial" },
      { type: "error", data: JSON.stringify({ message: "Internal server error", correlationId: "c-1" }) },
    ];
    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "hello" });
    });

    const contents = useChatStore.getState().messages.map((m) => m.content);
    expect(contents[0]).toBe("hello");
    expect(contents[1]).toContain("Internal server error");
    expect(h.toastError).not.toHaveBeenCalled();
  });

  // Pinned against the backend: every code the streaming endpoint emits for a
  // pre-turn refusal must be treated as unconsumed, and nothing else.
  it("covers exactly the codes the backend emits for pre-turn refusals", () => {
    const root = "../../src/main/java/ai/labs/eddi/engine";
    const streaming = readFileSync(`${root}/internal/RestAgentEngineStreaming.java`, "utf8");
    const mapper = readFileSync(`${root}/exception/InputTooLargeExceptionMapper.java`, "utf8");
    const emitted = new Set([...streaming.matchAll(/code = "([a-z_]+)";/g)].map((m) => m[1]!));
    const inputTooLarge = /ERROR_CODE = "([a-z_]+)"/.exec(mapper)?.[1];
    expect(inputTooLarge).toBeTruthy();
    emitted.add(inputTooLarge!);
    expect([...UNCONSUMED_STREAM_ERROR_CODES].sort()).toEqual([...emitted].sort());
  });

  // A detached stream drains so the turn can finish, but a proxy that swallows
  // the terminal frame used to keep it (and its mutation) open for good.
  it("aborts a detached stream once the grace period has passed", async () => {
    h.frames = [{ type: "token", data: "A" }, doneFrame({ output: [] })];
    h.gateAt = 1;
    const gateReached = new Promise<void>((r) => (h.reachedGate = r));
    const { result } = renderHook(() => useSendMessage(), { wrapper });
    let sending!: Promise<unknown>;
    act(() => {
      sending = result.current.mutateAsync({ message: "hi A" });
    });
    await gateReached;

    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    try {
      act(() => useChatStore.getState().setSelectedAgent("agent-b", "B"));
      vi.advanceTimersByTime(DETACHED_STREAM_GRACE_MS - 1);
      expect(h.aborted).toBe(false);
      vi.advanceTimersByTime(1);
      expect(h.aborted).toBe(true);
    } finally {
      vi.useRealTimers();
    }
    await act(async () => {
      await sending;
    });
    expect(useChatStore.getState().messages).toEqual([]);
  });

  it("closes the live debug turn when the transcript is replaced", () => {
    useDebugStore.getState().addEvent({
      type: "task_start",
      taskId: "t1",
      taskType: "ai.labs.llm",
      index: 0,
      timestamp: Date.now(),
    });
    expect(useDebugStore.getState().currentTurnEvents).toHaveLength(1);

    useChatStore.getState().setSelectedAgent("agent-b", "B");

    expect(useDebugStore.getState().currentTurnEvents).toHaveLength(0);
  });
});
