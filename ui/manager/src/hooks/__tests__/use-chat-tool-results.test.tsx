import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { type ReactNode } from "react";
import { type SSEEvent } from "@eddi/app-kit/lib/api/chat";

const h = vi.hoisted(() => ({ frames: [] as Array<{ type: string; data: string }> }));

vi.mock("@eddi/app-kit/lib/api/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@eddi/app-kit/lib/api/chat")>();
  return {
    ...actual,
    sendMessageStreaming: async function* () {
      for (const frame of h.frames) {
        yield frame as SSEEvent;
      }
    },
  };
});

import { chatToolActivity, useChatStore, useSendMessage } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

const toolResult = (tool: string, ok: boolean, summary: string) => ({
  type: "tool_result",
  data: JSON.stringify({ tool, ok, summary }),
});

/**
 * T1.9 / T1.6 — the chat's controller files each `tool_result` frame (B6)
 * under the conversation and the step of the turn it belongs to.
 */
describe("use-chat — tool_result", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    chatToolActivity.clear();
    h.frames = [];
    useChatStore.setState({ selectedAgentId: "agent1", conversationId: "conv1", streamingEnabled: true });
    useChatStore.getState().replaceMessages([{ id: "a0", role: "agent", content: "Welcome", timestamp: 1 }]);
  });

  async function send(message: string) {
    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message });
    });
  }

  it("records the turn's calls under its step, in order, and nothing of them in the transcript", async () => {
    h.frames = [
      { type: "tool_call", data: JSON.stringify({ tool: "search" }) },
      toolResult("search", true, "3 hits"),
      { type: "tool_call", data: JSON.stringify({ tool: "send_email" }) },
      toolResult("send_email", false, "HTTP 500"),
      { type: "token", data: "Done." },
    ];
    await send("hi");
    expect(chatToolActivity.forStep("conv1", 1)?.results).toEqual([
      { tool: "search", ok: true, summary: "3 hits" },
      { tool: "send_email", ok: false, summary: "HTTP 500" },
    ]);
    const transcript = useChatStore.getState().messages.map((m) => m.content).join("\n");
    expect(transcript).not.toContain("3 hits");
    expect(transcript).not.toContain("HTTP 500");
  });

  it("files a second turn under the next step and ignores a malformed frame", async () => {
    h.frames = [toolResult("search", true, "first")];
    await send("one");
    h.frames = [{ type: "tool_result", data: "{not json" }, toolResult("lookup", true, "second")];
    await send("two");
    expect(chatToolActivity.forStep("conv1", 1)?.results.map((r) => r.summary)).toEqual(["first"]);
    expect(chatToolActivity.forStep("conv1", 2)?.results.map((r) => r.summary)).toEqual(["second"]);
  });

  it("starts a turn that retakes an undone step without the undone turn's calls", async () => {
    // Step 1 ran a tool; then an undo took it back (the transcript holds only
    // the welcome again), and a new question takes step 1 and runs none.
    chatToolActivity.record("conv1", 1, { tool: "send_email", ok: true, summary: "sent" });
    chatToolActivity.record("conv1", 2, { tool: "later", ok: true, summary: "" });
    h.frames = [{ type: "token", data: "No tools this time." }];
    await send("again");
    expect(chatToolActivity.forStep("conv1", 1)).toBeUndefined();
    expect(chatToolActivity.forStep("conv1", 2)).toBeUndefined();
  });
});
