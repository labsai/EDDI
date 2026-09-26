import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { type SSEEvent } from "@/lib/api/chat";

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
}));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* () {
      for (let i = 0; i < h.frames.length; i++) {
        if (i === h.gateAt) {
          await new Promise<void>((resolve) => {
            h.release = resolve;
            h.reachedGate();
          });
        }
        yield h.frames[i] as SSEEvent;
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
