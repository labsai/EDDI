import { describe, it, expect, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { server } from "@/test/mocks/server";
import { EXECUTING_TURN_POLL_MS, useChatStore, useLoadConversation } from "@/hooks/use-chat";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

afterEach(() => act(() => useChatStore.getState().reset()));

/**
 * A user who leaves a conversation while its reply streams, and reopens it
 * before the turn finishes, used to get one read of it: the reply was not in
 * it, and the stream that would have delivered it had been detached when they
 * left. The transcript stayed without the reply until a manual reload.
 */
const EXECUTING = {
  conversationId: "conv-x",
  agentId: "a1",
  agentVersion: 1,
  conversationState: "IN_PROGRESS",
  environment: "production",
  conversationSteps: [
    { conversationStep: [{ key: "input:initial", value: "first question" }] },
  ],
  conversationOutputs: [
    {
      output: [
        { type: "text", text: "First answer. Paste your key" },
        { type: "inputField", subType: "password", label: "API Key" },
      ],
    },
  ],
};

const SETTLED = {
  ...EXECUTING,
  conversationState: "READY",
  conversationSteps: [
    ...EXECUTING.conversationSteps,
    { conversationStep: [{ key: "input:initial", value: "second question" }] },
  ],
  conversationOutputs: [
    EXECUTING.conversationOutputs[0],
    { output: [{ type: "text", text: "The late answer." }] },
  ],
};

function agentTexts() {
  return useChatStore
    .getState()
    .messages.filter((m) => m.role === "agent")
    .map((m) => m.content);
}

describe("reopening a conversation whose turn is still executing", () => {
  it("follows the turn until it settles and then shows its reply", async () => {
    let reads = 0;
    server.use(
      http.get("*/agents/:conversationId", () => {
        reads++;
        return HttpResponse.json(reads < 3 ? EXECUTING : SETTLED);
      }),
    );
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-x" });
    });

    // Busy while the turn runs: no send into it, and the field its predecessor
    // asked for (being answered right now) is not offered.
    expect(useChatStore.getState().isProcessing).toBe(true);
    expect(useChatStore.getState().activeInputField).toBeNull();
    expect(agentTexts()).not.toContain("The late answer.");

    await waitFor(() => expect(agentTexts()).toContain("The late answer."), {
      timeout: EXECUTING_TURN_POLL_MS * 4,
    });
    expect(useChatStore.getState().isProcessing).toBe(false);
    expect(useChatStore.getState().conversationId).toBe("conv-x");
    expect(reads).toBe(3);
  });

  it("stops following, and writes nothing, once the user moves on", async () => {
    let reads = 0;
    server.use(
      http.get("*/agents/:conversationId", () => {
        reads++;
        return HttpResponse.json(reads < 2 ? EXECUTING : SETTLED);
      }),
    );
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-x" });
    });
    act(() => useChatStore.getState().setSelectedAgent("a2", "Other"));

    await new Promise((r) => setTimeout(r, EXECUTING_TURN_POLL_MS * 2));
    const state = useChatStore.getState();
    expect(state.messages).toEqual([]);
    expect(state.conversationId).toBeNull();
    expect(state.isProcessing).toBe(false);
    expect(reads).toBe(1);
  });

  it("does not follow a conversation that is not executing", async () => {
    let reads = 0;
    server.use(
      http.get("*/agents/:conversationId", () => {
        reads++;
        return HttpResponse.json(SETTLED);
      }),
    );
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-x" });
    });

    expect(useChatStore.getState().isProcessing).toBe(false);
    await new Promise((r) => setTimeout(r, EXECUTING_TURN_POLL_MS + 200));
    expect(reads).toBe(1);
  });
});
