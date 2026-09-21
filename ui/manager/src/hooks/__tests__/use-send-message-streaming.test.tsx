import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";

// Capture the InputData handed to the streaming API so we can assert the
// attachment_* context rides the STREAMING path (the production default) — the
// non-streaming path is covered by the ChatPanel integration tests.
const h = vi.hoisted(() => ({ streamInput: null as null | { input?: string; context?: Record<string, unknown> } }));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* (
      _env: string,
      _agentId: string,
      _conversationId: string,
      inputData: { input?: string; context?: Record<string, unknown> },
    ) {
      h.streamInput = inputData;
      yield { type: "done" as const, data: "{}" };
    },
  };
});

import { useChatStore, useSendMessage } from "@/hooks/use-chat";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe("useSendMessage — streaming path forwards attachment context", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    h.streamInput = null;
  });

  it("passes attachment_* context to sendMessageStreaming when streaming is enabled", async () => {
    useChatStore.setState({
      selectedAgentId: "agent1",
      conversationId: "conv1",
      streamingEnabled: true,
    });

    const { result } = renderHook(() => useSendMessage(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({
        message: "look at this",
        attachments: [
          { storageRef: "stream-ref-1", fileName: "a.txt", mimeType: "text/plain", sizeBytes: 3 },
        ],
      });
    });

    expect(h.streamInput?.input).toBe("look at this");
    expect(
      (h.streamInput?.context as { attachment_0?: { value?: { storageRef?: string } } })
        ?.attachment_0?.value?.storageRef,
    ).toBe("stream-ref-1");
  });

  it("omits context on a plain streaming turn with no attachments", async () => {
    useChatStore.setState({
      selectedAgentId: "agent1",
      conversationId: "conv1",
      streamingEnabled: true,
    });

    const { result } = renderHook(() => useSendMessage(), { wrapper });

    await act(async () => {
      await result.current.mutateAsync({ message: "hi" });
    });

    expect(h.streamInput?.input).toBe("hi");
    expect(h.streamInput?.context).toBeUndefined();
  });
});
