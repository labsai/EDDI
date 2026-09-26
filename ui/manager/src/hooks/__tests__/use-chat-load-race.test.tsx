import { describe, it, expect, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { server } from "@/test/mocks/server";
import {
  useChatStore,
  useLoadConversation,
  useResumeOrStartConversation,
  useStartConversation,
} from "@/hooks/use-chat";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

afterEach(() => act(() => useChatStore.getState().reset()));

function snapshot(conversationId: string, text: string) {
  return {
    agentId: "any",
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
 * A slow read of a conversation must not install it once the user has moved
 * on. Before the load was bound to the transcript generation, a read of agent
 * A's conversation that returned after agent B had been picked and its new
 * conversation started replaced B's conversation with A's, under B's name, so
 * every send then went to agent A.
 */
describe("a conversation load in flight never overwrites what replaced it", () => {
  it("keeps agent B's new conversation when agent A's read returns late", async () => {
    const readA = gate();
    server.use(
      http.get("*/agents/:conversationId", async ({ params }) => {
        if (params.conversationId === "conv-a") {
          readA.reached();
          await readA.opened;
          return HttpResponse.json(snapshot("conv-a", "A's old reply"));
        }
        return HttpResponse.json(snapshot(String(params.conversationId), "B's greeting"));
      }),
      http.post("*/agents/:agentId/start", ({ params }) =>
        HttpResponse.json({ location: `/agents/conv-new-${params.agentId}` }),
      ),
    );
    const load = renderHook(() => useLoadConversation(), { wrapper }).result;
    const start = renderHook(() => useStartConversation(), { wrapper }).result;

    act(() => useChatStore.getState().setSelectedAgent("agent-a", "A"));
    let loading!: Promise<unknown>;
    act(() => {
      loading = load.current.mutateAsync({ agentId: "agent-a", conversationId: "conv-a" });
    });
    await readA.arrived;

    act(() => useChatStore.getState().setSelectedAgent("agent-b", "B"));
    await act(async () => {
      await start.current.mutateAsync({ agentId: "agent-b", environment: "production" });
    });
    expect(useChatStore.getState().conversationId).toBe("conv-new-agent-b");

    await act(async () => {
      readA.open();
      await loading;
    });

    const state = useChatStore.getState();
    expect(state.selectedAgentId).toBe("agent-b");
    expect(state.conversationId).toBe("conv-new-agent-b");
    expect(state.messages.map((m) => m.content)).not.toContain("A's old reply");
  });

  it("keeps a new conversation started while a history row was still loading", async () => {
    const readOld = gate();
    server.use(
      http.get("*/agents/:conversationId", async ({ params }) => {
        if (params.conversationId === "conv-old") {
          readOld.reached();
          await readOld.opened;
          return HttpResponse.json(snapshot("conv-old", "old transcript"));
        }
        return HttpResponse.json(snapshot(String(params.conversationId), "fresh greeting"));
      }),
      http.post("*/agents/:agentId/start", () => HttpResponse.json({ location: "/agents/conv-fresh" })),
    );
    const load = renderHook(() => useLoadConversation(), { wrapper }).result;
    const start = renderHook(() => useStartConversation(), { wrapper }).result;

    act(() => useChatStore.getState().setSelectedAgent("agent-a", "A"));
    let loading!: Promise<unknown>;
    act(() => {
      loading = load.current.mutateAsync({ agentId: "agent-a", conversationId: "conv-old" });
    });
    await readOld.arrived;

    // "New conversation": clear, then start.
    await act(async () => {
      useChatStore.getState().clearMessages();
      await start.current.mutateAsync({ agentId: "agent-a", environment: "production" });
    });
    await act(async () => {
      readOld.open();
      await loading;
    });

    expect(useChatStore.getState().conversationId).toBe("conv-fresh");
    expect(useChatStore.getState().messages.map((m) => m.content)).not.toContain("old transcript");
  });

  it("opens nothing for agent A when B was picked while A's history was loading", async () => {
    const history = gate();
    let startedFor: string | null = null;
    server.use(
      http.get("*/conversationstore/conversations", async () => {
        history.reached();
        await history.opened;
        return HttpResponse.json([]);
      }),
      http.post("*/agents/:agentId/start", ({ params }) => {
        startedFor = String(params.agentId);
        return HttpResponse.json({ location: "/agents/conv-x" });
      }),
    );
    const open = renderHook(() => useResumeOrStartConversation(), { wrapper }).result;

    act(() => useChatStore.getState().setSelectedAgent("agent-a", "A"));
    let opening!: Promise<unknown>;
    act(() => {
      opening = open.current.mutateAsync({ agentId: "agent-a", environment: "production" });
    });
    await history.arrived;

    act(() => useChatStore.getState().setSelectedAgent("agent-b", "B"));
    await act(async () => {
      history.open();
      await opening;
    });

    expect(startedFor).toBeNull();
    expect(useChatStore.getState().conversationId).toBeNull();
  });
});
