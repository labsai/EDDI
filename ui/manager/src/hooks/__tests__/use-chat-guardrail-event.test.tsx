import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { type SSEEvent } from "@/lib/api/chat";

const h = vi.hoisted(() => ({ frames: [] as Array<{ type: string; data: string }> }));

vi.mock("@/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* () {
      for (const frame of h.frames) {
        yield frame as SSEEvent;
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

function lastAgent() {
  const agents = useChatStore.getState().messages.filter((m) => m.role === "agent");
  return agents[agents.length - 1]!;
}

describe("use-chat — guardrail_triggered", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    useChatStore.setState({ selectedAgentId: "agent1", conversationId: "conv1", streamingEnabled: true });
  });

  async function send() {
    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message: "hi" });
    });
  }

  it("attaches each fired guardrail to the agent bubble of the turn, in order", async () => {
    h.frames = [
      { type: "guardrail_triggered", data: JSON.stringify({ type: "prompt-injection", phase: "input", action: "block", taskId: "t1" }) },
      { type: "guardrail_triggered", data: JSON.stringify({ type: "pii-redaction", phase: "output", action: "redact", taskId: "t1" }) },
      { type: "token", data: "Request blocked" },
    ];
    await send();
    const agent = lastAgent();
    expect(agent.guardrails).toEqual([
      { type: "prompt-injection", phase: "input", action: "block" },
      { type: "pii-redaction", phase: "output", action: "redact" },
    ]);
    expect(agent.content).toBe("Request blocked");
  });

  it("does not end the turn, so tokens after the event still arrive", async () => {
    h.frames = [
      { type: "guardrail_triggered", data: JSON.stringify({ type: "max-length", phase: "output", action: "warn", taskId: "t1" }) },
      { type: "token", data: "still streaming" },
    ];
    await send();
    const agent = lastAgent();
    expect(agent.content).toBe("still streaming");
  });

  it("keeps the notice when the done snapshot back-fills the answer text", async () => {
    // A blocked turn can carry no token at all: the snapshot is its only text.
    h.frames = [
      { type: "guardrail_triggered", data: JSON.stringify({ type: "prompt-injection", phase: "input", action: "block", taskId: "t1" }) },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: "I can't process that request." }] }],
        }),
      },
    ];
    await send();
    const agent = lastAgent();
    expect(agent.content).toBe("I can't process that request.");
    expect(agent.guardrails).toEqual([{ type: "prompt-injection", phase: "input", action: "block" }]);
  });

  it("ignores a malformed payload", async () => {
    h.frames = [
      { type: "guardrail_triggered", data: "not json" },
      { type: "guardrail_triggered", data: JSON.stringify({ phase: "input" }) },
      { type: "token", data: "ok" },
    ];
    await send();
    const agent = lastAgent();
    expect(agent.guardrails).toBeUndefined();
    expect(agent.content).toBe("ok");
  });
});
