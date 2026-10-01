import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { server } from "@/test/mocks/server";

const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({ toast: { error: toastError, success: vi.fn() } }));

import { useChatStore, useLoadConversation, useSendMessage } from "@/hooks/use-chat";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

function snapshot(conversationId: string, text: string) {
  return {
    agentId: "agent1",
    agentVersion: 1,
    conversationId,
    conversationState: "READY",
    environment: "production",
    conversationSteps: [{ conversationStep: [{ key: "input:initial", value: "q" }] }],
    conversationOutputs: [{ input: "q", output: [{ type: "text", text }] }],
  };
}

/** A gate the test opens, plus a promise that settles once a request reached it. */
function gate() {
  let open!: () => void;
  let reached!: () => void;
  const opened = new Promise<void>((r) => (open = r));
  const arrived = new Promise<void>((r) => (reached = r));
  return { open, reached: () => reached(), opened, arrived };
}

/**
 * A load reads before it replaces anything, so while the read is in flight the
 * transcript and `conversationId` on screen still belong to the conversation
 * being left. A send in that window went to the OLD conversation on the server,
 * then the load installed over it — the message vanished from view while still
 * having been delivered.
 */
describe("a send while another conversation is loading", () => {
  const says: string[] = [];

  beforeEach(() => {
    says.length = 0;
    toastError.mockReset();
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support");
    useChatStore.getState().setConversationId("conv-current");
    useChatStore.setState({ streamingEnabled: false });
  });

  afterEach(() => act(() => useChatStore.getState().reset()));

  function recordSays() {
    return http.post("*/agents/:conversationId", ({ params }) => {
      says.push(String(params.conversationId));
      return HttpResponse.json(snapshot(String(params.conversationId), "reply"));
    });
  }

  it("is refused and reaches neither the server nor the transcript", async () => {
    const read = gate();
    server.use(
      recordSays(),
      http.get("*/agents/:conversationId", async ({ params }) => {
        read.reached();
        await read.opened;
        return HttpResponse.json(snapshot(String(params.conversationId), "the older one"));
      }),
    );
    const load = renderHook(() => useLoadConversation(), { wrapper }).result;
    const send = renderHook(() => useSendMessage(), { wrapper }).result;

    let loading!: Promise<unknown>;
    act(() => {
      loading = load.current.mutateAsync({ agentId: "agent1", conversationId: "conv-older" });
    });
    await read.arrived;
    expect(useChatStore.getState().loadingConversationId).toBe("conv-older");

    act(() => send.current.mutate({ message: "meant for the older one" }));
    await waitFor(() => expect(send.current.isError).toBe(true));

    expect(says).toEqual([]);
    expect(useChatStore.getState().messages).toEqual([]);
    expect(useChatStore.getState().isProcessing).toBe(false);
    expect(toastError).toHaveBeenCalledWith(expect.stringContaining("still loading"));

    read.open();
    await act(async () => {
      await loading;
    });
    expect(useChatStore.getState().conversationId).toBe("conv-older");
    expect(useChatStore.getState().loadingConversationId).toBeNull();

    // Once loaded, a send goes to the conversation now on screen.
    await act(async () => {
      await send.current.mutateAsync({ message: "now it can go" });
    });
    expect(says).toEqual(["conv-older"]);
  });

  it("is allowed again when the load fails, against the conversation still open", async () => {
    server.use(
      recordSays(),
      http.get("*/agents/:conversationId", () => new HttpResponse(null, { status: 500 })),
    );
    const load = renderHook(() => useLoadConversation(), { wrapper }).result;
    const send = renderHook(() => useSendMessage(), { wrapper }).result;

    await act(async () => {
      await load.current
        .mutateAsync({ agentId: "agent1", conversationId: "conv-older" })
        .catch(() => undefined);
    });
    expect(useChatStore.getState().loadingConversationId).toBeNull();
    expect(useChatStore.getState().conversationId).toBe("conv-current");

    await act(async () => {
      await send.current.mutateAsync({ message: "still here" });
    });
    expect(says).toEqual(["conv-current"]);
  });

  it("does not stay blocked when the transcript is replaced mid-load", async () => {
    const read = gate();
    server.use(
      http.get("*/agents/:conversationId", async ({ params }) => {
        read.reached();
        await read.opened;
        return HttpResponse.json(snapshot(String(params.conversationId), "late"));
      }),
    );
    const load = renderHook(() => useLoadConversation(), { wrapper }).result;

    let loading!: Promise<unknown>;
    act(() => {
      loading = load.current.mutateAsync({ agentId: "agent1", conversationId: "conv-older" });
    });
    await read.arrived;

    act(() => useChatStore.getState().setSelectedAgent("agent2", "Other"));
    expect(useChatStore.getState().loadingConversationId).toBeNull();

    read.open();
    await act(async () => {
      await loading;
    });
    // The superseded load neither installed nor re-armed the block.
    expect(useChatStore.getState().conversationId).toBeNull();
    expect(useChatStore.getState().loadingConversationId).toBeNull();
  });
});
