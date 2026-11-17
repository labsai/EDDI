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

import { useChatStore, useSendMessage } from "@eddi/app-kit/hooks/use-chat";
import { useDebugStore } from "@eddi/app-kit/hooks/use-debug-events";

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

/**
 * Turns that ChatPanel shows since it runs on @eddi/conversation's controller
 * (phase 3c): designer images, and the output items @eddi/core reads that the
 * Manager's own reader dropped.
 */
describe("use-chat — what a reply can carry", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useDebugStore.getState().reset();
    h.frames = [];
    useChatStore.setState({ selectedAgentId: "agent1", conversationId: "conv1", streamingEnabled: true });
  });

  async function send(message = "hello") {
    const { result } = renderHook(() => useSendMessage(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ message });
    });
  }

  it("shows the image output items the agent designer configured", async () => {
    h.frames = [
      { type: "token", data: "Here is the chart." },
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [
            {
              output: [
                { type: "text", text: "Here is the chart." },
                { type: "image", uri: "https://cdn.example/chart.png", alt: "Chart" },
                // Never a script URL, whatever the configuration says.
                { type: "image", uri: "javascript:alert(1)", alt: "x" },
              ],
            },
          ],
        }),
      },
    ];
    await send();
    const agents = useChatStore.getState().messages.filter((m) => m.role === "agent");
    expect(agents.map((m) => m.content)).toEqual(["Here is the chart.", ""]);
    expect(agents[1]!.images).toEqual([{ uri: "https://cdn.example/chart.png", alt: "Chart" }]);
  });

  it("shows a button's label and a link that the older reader dropped", async () => {
    h.frames = [
      {
        type: "done",
        data: JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [
            {
              output: [
                { type: "button", label: "Confirm" },
                { type: "applicationLink", label: "Docs", path: "https://docs.example/a" },
              ],
            },
          ],
        }),
      },
    ];
    await send();
    const agents = useChatStore.getState().messages.filter((m) => m.role === "agent");
    expect(agents.map((m) => m.content)).toEqual(["**Confirm**\n\n[Docs](<https://docs.example/a>)"]);
  });

  it("still reads the flat output:text keys older snapshots carry", async () => {
    useChatStore.setState({ streamingEnabled: false });
    vi.spyOn(await import("@eddi/app-kit/lib/api/chat"), "sendMessage").mockResolvedValue({
      conversationSteps: [],
      conversationOutputs: [{ "output:text:respond": "Legacy reply" }],
    } as never);
    await send();
    const agents = useChatStore.getState().messages.filter((m) => m.role === "agent");
    expect(agents.map((m) => m.content)).toEqual(["Legacy reply"]);
  });
});
