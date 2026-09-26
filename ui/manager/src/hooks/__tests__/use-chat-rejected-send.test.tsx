import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";

const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({ toast: { error: toastError, success: vi.fn() } }));

import { useChatStore, useSendMessage } from "@/hooks/use-chat";

/**
 * A 409 on `say` is not one condition. The same status answers "awaiting human
 * approval", "processing another turn — retry shortly", "agent version
 * mismatch" and (with the queued-turn fix) "the conversation changed while your
 * message was queued". Only the first one is a pause; the chat used to show the
 * approval banner for all of them.
 */
function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function snapshotIn(state: string) {
  return HttpResponse.json({
    conversationState: state,
    conversationSteps: [],
    conversationOutputs: [],
  });
}

describe("useSendMessage — a refused send is classified by the conversation's state", () => {
  beforeEach(() => {
    toastError.mockReset();
    useChatStore.getState().reset();
    useChatStore.setState({
      selectedAgentId: "agent1",
      conversationId: "conv1",
      streamingEnabled: false,
    });
  });

  it("shows the pause banner when the conversation really is awaiting approval", async () => {
    server.use(
      http.post("*/agents/:conversationId", () =>
        new HttpResponse("Conversation is awaiting human approval", { status: 409 }),
      ),
      http.get("*/agents/:conversationId", () => snapshotIn("AWAITING_HUMAN")),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hello?" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    const state = useChatStore.getState();
    expect(state.isPaused).toBe(true);
    expect(state.messages).toEqual([]);
    expect(toastError).not.toHaveBeenCalled();
  });

  it("does not claim a pause for a busy conversation; it says why the message was not sent", async () => {
    const reason =
      "Conversation is processing another turn — your message was not processed; retry shortly";
    server.use(
      http.post("*/agents/:conversationId", () => new HttpResponse(reason, { status: 409 })),
      http.get("*/agents/:conversationId", () => snapshotIn("READY")),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hello?" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    const state = useChatStore.getState();
    expect(state.isPaused).toBe(false);
    // Not consumed, so not left in the transcript either — and no error bubble,
    // whose "Retry Last Step" would re-run a turn that never happened.
    expect(state.messages).toEqual([]);
    expect(state.isProcessing).toBe(false);
    expect(toastError).toHaveBeenCalledWith(expect.stringContaining(reason));
  });

  it("keeps the pause reading when the state cannot be read", async () => {
    server.use(
      http.post("*/agents/:conversationId", () => new HttpResponse(null, { status: 409 })),
      http.get("*/agents/:conversationId", () => new HttpResponse(null, { status: 500 })),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hello?" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(useChatStore.getState().isPaused).toBe(true);
  });

  it("classifies a 409 on the streaming endpoint the same way", async () => {
    useChatStore.setState({ streamingEnabled: true });
    server.use(
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse("Agent version mismatch", { status: 409 }),
      ),
      http.get("*/agents/:conversationId", () => snapshotIn("READY")),
    );

    const { result } = renderHook(() => useSendMessage(), { wrapper });
    result.current.mutate({ message: "hello?" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    const state = useChatStore.getState();
    expect(state.isPaused).toBe(false);
    expect(state.messages).toEqual([]);
    expect(toastError).toHaveBeenCalledWith(expect.stringContaining("Agent version mismatch"));
  });
});
