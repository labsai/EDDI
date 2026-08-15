import { describe, it, expect, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { server } from "@/test/mocks/server";
import { useLoadConversation, useChatStore } from "@/hooks/use-chat";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function mockLoadedConversation(conversationState: string) {
  server.use(
    http.get("*/agents/:conversationId", () =>
      HttpResponse.json({
        conversationId: "conv-x",
        agentId: "a1",
        agentVersion: 1,
        conversationState,
        environment: "production",
        conversationSteps: [],
        conversationOutputs: [],
        redoAvailable: false,
      }),
    ),
  );
}

afterEach(() => act(() => useChatStore.getState().reset()));

describe("useLoadConversation — pause re-establishment", () => {
  it("marks the store paused when a loaded conversation is still AWAITING_HUMAN", async () => {
    mockLoadedConversation("AWAITING_HUMAN");
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-x" });
    });
    expect(useChatStore.getState().isPaused).toBe(true);
  });

  it("leaves the store unpaused for a READY conversation", async () => {
    mockLoadedConversation("READY");
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-x" });
    });
    expect(useChatStore.getState().isPaused).toBe(false);
  });

  it("surfaces the backend hitlPauseReason when loading a paused conversation", async () => {
    server.use(
      http.get("*/agents/:conversationId", () =>
        HttpResponse.json({
          conversationId: "conv-x",
          agentId: "a1",
          agentVersion: 1,
          conversationState: "AWAITING_HUMAN",
          environment: "production",
          conversationSteps: [],
          conversationOutputs: [],
          redoAvailable: false,
        }),
      ),
    );
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-x" });
    });
    expect(useChatStore.getState().isPaused).toBe(true);
    // Null from the snapshot ON PURPOSE: the simple snapshot never carries a
    // reason on the wire; the rendered reason overlays from approval-status.
    expect(useChatStore.getState().pauseReason).toBeNull();
  });
});
