import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@eddi/app-kit/test/mocks/server";
import { type SSEEvent } from "@eddi/app-kit/lib/api/chat";

// The streaming path yields these frames and then fails the way a dropped
// connection does: by throwing out of the reader.
const h = vi.hoisted(() => ({ frames: [] as Array<{ type: string; data: string }> }));

vi.mock("@eddi/app-kit/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@eddi/app-kit/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* () {
      for (const frame of h.frames) {
        yield frame as SSEEvent;
      }
      throw new Error("socket closed");
    },
  };
});

import { chatConversation, useChatStore, useSendMessage } from "@eddi/app-kit/hooks/use-chat";
import { RECONNECT_DELAYS_MS } from "@eddi/conversation";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

/**
 * A send that fails after it was consumed keeps the user's message and reports
 * the failure. It used to leave the bubble it had opened still "typing" under
 * the error, and the thinking indicator on.
 */
describe("useSendMessage — a failed send clears its typing state", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    // The turn is lost: the reconnect (below) is its own case.
    chatConversation.setOptions({ reconnectDelaysMs: [] });
    useChatStore.setState({ selectedAgentId: "agent1", conversationId: "conv1" });
  });

  it("drops the empty streaming placeholder and turns thinking off", async () => {
    useChatStore.setState({ streamingEnabled: true });
    h.frames = [{ type: "task_start", data: JSON.stringify({ taskId: "t1", taskType: "llm", index: 0 }) }];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hi" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    const state = useChatStore.getState();
    expect(state.isThinking).toBe(false);
    expect(state.isProcessing).toBe(false);
    expect(state.messages.some((m) => m.isStreaming)).toBe(false);
    expect(state.messages.map((m) => m.role)).toEqual(["user", "agent"]);
    const last = state.messages[state.messages.length - 1]!;
    expect(last.isError).toBe(true);
    expect(last.content).toContain("socket closed");
  });

  it("keeps a partly streamed reply but closes it", async () => {
    useChatStore.setState({ streamingEnabled: true });
    h.frames = [{ type: "token", data: "Half an ans" }];

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hi" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    const agents = useChatStore.getState().messages.filter((m) => m.role === "agent");
    expect(agents).toHaveLength(2);
    expect(agents[0]!.content).toBe("Half an ans");
    expect(agents[0]!.isStreaming).toBe(false);
    expect(agents[1]!.isError).toBe(true);
  });

  it("follows a turn whose stream dropped mid-reply and shows the reply it settled with", async () => {
    chatConversation.setOptions({ reconnectDelaysMs: [0, 0] });
    useChatStore.setState({ streamingEnabled: true, selectedAgentId: "a1", conversationId: "c1" });
    h.frames = [{ type: "token", data: "Half an ans" }];
    let reads = 0;
    server.use(
      http.get("*/agents/c1", () => {
        reads += 1;
        return HttpResponse.json(
          reads === 1
            ? { conversationState: "IN_PROGRESS" }
            : { conversationState: "READY", conversationOutputs: [{ output: [{ type: "text", text: "Half an answer, whole" }] }] },
        );
      }),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hi" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const state = useChatStore.getState();
    const agents = state.messages.filter((m) => m.role === "agent");
    expect(agents.map((m) => m.content)).toEqual(["Half an answer, whole"]);
    expect(agents.some((m) => m.isError)).toBe(false);
    expect(state.reconnecting).toBe(false);
    expect(state.isProcessing).toBe(false);
  });

  it("replaces the non-streaming typing indicator with the error and its HTTP detail", async () => {
    useChatStore.setState({ streamingEnabled: false });
    server.use(http.post("*/agents/:conversationId", () => new HttpResponse(null, { status: 500 })));

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hi" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    const state = useChatStore.getState();
    expect(state.messages.some((m) => m.isStreaming)).toBe(false);
    expect(state.messages.map((m) => m.role)).toEqual(["user", "agent"]);
    const last = state.messages[1]!;
    expect(last.isError).toBe(true);
    expect(last.content).toContain("HTTP 500");
  });
});

afterEach(() => {
  chatConversation.setOptions({ reconnectDelaysMs: RECONNECT_DELAYS_MS });
});
