import { describe, it, expect, afterEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { server } from "@/test/mocks/server";
import {
  useChatStore,
  useDeployedAgents,
  useLoadConversation,
  useStartConversation,
} from "@/hooks/use-chat";

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

afterEach(() => act(() => useChatStore.getState().reset()));

/**
 * The step's `input:initial` holds the raw text of every turn, secret or not;
 * the turn's conversation OUTPUT carries what should be displayed, which for a
 * secret turn is EDDI's placeholder. Rebuilding bubbles from `input:initial`
 * put a pasted API key back on screen after any reload.
 */
const SECRET_TURN = {
  conversationId: "conv-s",
  agentId: "a1",
  agentVersion: 1,
  conversationState: "READY",
  environment: "production",
  conversationSteps: [
    { conversationStep: [{ key: "actions", value: ["CONVERSATION_START"] }] },
    {
      conversationStep: [
        { key: "input:initial", value: "sk-live-DO-NOT-SHOW" },
        { key: "actions", value: ["got_key"] },
      ],
    },
    {
      conversationStep: [
        { key: "input:initial", value: "what model do you use?" },
        { key: "actions", value: ["answer"] },
      ],
    },
  ],
  conversationOutputs: [
    {
      output: [
        { type: "text", text: "Paste your API key" },
        { type: "inputField", subType: "password", label: "API Key" },
      ],
    },
    { input: "<secret input>", output: [{ type: "text", text: "Key stored." }] },
    { input: "what model do you use?", output: [{ type: "text", text: "A good one." }] },
  ],
  redoAvailable: false,
};

describe("rebuilding the transcript never un-masks a secret turn", () => {
  it("shows the mask, not input:initial, for a turn EDDI recorded as secret", async () => {
    server.use(http.get("*/agents/:conversationId", () => HttpResponse.json(SECRET_TURN)));
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-s" });
    });

    const userBubbles = useChatStore
      .getState()
      .messages.filter((m) => m.role === "user")
      .map((m) => m.content);
    expect(userBubbles).toEqual(["●●●●●●●●", "what model do you use?"]);
    expect(JSON.stringify(useChatStore.getState().messages)).not.toContain("sk-live");
  });

  it("falls back to input:initial when the output carries no input", async () => {
    server.use(
      http.get("*/agents/:conversationId", () =>
        HttpResponse.json({
          ...SECRET_TURN,
          conversationOutputs: [
            SECRET_TURN.conversationOutputs[0],
            { output: [{ type: "text", text: "Key stored." }] },
            { output: [{ type: "text", text: "A good one." }] },
          ],
        }),
      ),
    );
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-s" });
    });

    const userBubbles = useChatStore
      .getState()
      .messages.filter((m) => m.role === "user")
      .map((m) => m.content);
    expect(userBubbles).toContain("what model do you use?");
  });

  it("offers the masked field again when the reopened conversation's last reply asks for it", async () => {
    server.use(
      http.get("*/agents/:conversationId", () =>
        HttpResponse.json({
          ...SECRET_TURN,
          conversationSteps: SECRET_TURN.conversationSteps.slice(0, 1),
          conversationOutputs: SECRET_TURN.conversationOutputs.slice(0, 1),
        }),
      ),
    );
    const { result } = renderHook(() => useLoadConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", conversationId: "conv-s" });
    });

    expect(useChatStore.getState().activeInputField).toEqual(
      expect.objectContaining({ subType: "password", label: "API Key" }),
    );
  });
});

describe("useStartConversation", () => {
  it("honours an input field the greeting asks for", async () => {
    server.use(
      http.post("*/agents/:agentId/start", () =>
        HttpResponse.json({ location: "/agents/conv-new" }),
      ),
      http.get("*/agents/:conversationId", () =>
        HttpResponse.json({
          ...SECRET_TURN,
          conversationSteps: SECRET_TURN.conversationSteps.slice(0, 1),
          conversationOutputs: SECRET_TURN.conversationOutputs.slice(0, 1),
        }),
      ),
    );
    act(() => useChatStore.getState().setSelectedAgent("a1", "A"));
    const { result } = renderHook(() => useStartConversation(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ agentId: "a1", environment: "production" });
    });

    expect(useChatStore.getState().activeInputField?.subType).toBe("password");
  });

  it("does not install its conversation over an agent picked while it was starting", async () => {
    let releaseStart!: () => void;
    const startGate = new Promise<void>((r) => (releaseStart = r));
    let startReached!: () => void;
    const inFlight = new Promise<void>((r) => (startReached = r));
    server.use(
      http.post("*/agents/:agentId/start", async () => {
        startReached();
        await startGate;
        return HttpResponse.json({ location: "/agents/conv-for-a" });
      }),
    );
    act(() => useChatStore.getState().setSelectedAgent("a1", "A"));
    const { result } = renderHook(() => useStartConversation(), { wrapper });
    let starting!: Promise<unknown>;
    act(() => {
      starting = result.current.mutateAsync({ agentId: "a1", environment: "production" });
    });

    await inFlight;
    act(() => useChatStore.getState().setSelectedAgent("b2", "B"));
    await act(async () => {
      releaseStart();
      await starting;
    });

    expect(useChatStore.getState().selectedAgentId).toBe("b2");
    expect(useChatStore.getState().conversationId).toBeNull();
    expect(useChatStore.getState().messages).toEqual([]);
  });
});

describe("useDeployedAgents", () => {
  it("lists two distinct agents that share a name", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          { resource: "eddi://ai.labs.agent/agentstore/agents/aaa111?version=2", name: "Support Bot", description: "" },
          { resource: "eddi://ai.labs.agent/agentstore/agents/aaa111?version=1", name: "Support Bot", description: "" },
          { resource: "eddi://ai.labs.agent/agentstore/agents/bbb222?version=1", name: "Support Bot", description: "" },
        ]),
      ),
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );

    const { result } = renderHook(() => useDeployedAgents(), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const listed = result.current.data!.map((a) => `${a.id}@${a.version}`).sort();
    expect(listed).toEqual(["aaa111@2", "bbb222@1"]);
  });
});
