import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ChatPanel } from "../chat-panel";
import { useChatStore } from "@/hooks/use-chat";

/**
 * "Continue in Chat" on the conversation page navigates to
 * `/manage/chat?agentId=…&conversationId=…`. The panel read only `agentId`, so it
 * reopened the agent's MOST RECENT conversation instead of the one the user was
 * looking at.
 */
function snapshot(conversationId: string, text: string) {
  return {
    agentId: "agent1",
    agentVersion: 1,
    conversationId,
    conversationState: "READY",
    environment: "production",
    conversationSteps: [
      { conversationStep: [{ key: "input:initial", value: `question in ${conversationId}` }] },
    ],
    conversationOutputs: [
      { input: `question in ${conversationId}`, output: [{ type: "text", text }] },
    ],
  };
}

describe("ChatPanel — Continue in Chat opens the named conversation", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
  });

  it("loads the conversationId from the URL, not the agent's most recent one", async () => {
    const reads: string[] = [];
    server.use(
      http.get("*/agents/:conversationId", ({ params }) => {
        const id = String(params.conversationId);
        reads.push(id);
        return HttpResponse.json(snapshot(id, `answer from ${id}`));
      }),
    );

    renderWithProviders(<ChatPanel />, {
      initialRoute: "/manage/chat?agentId=agent1&conversationId=conv-older",
    });

    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-older"));
    expect(await screen.findByText("answer from conv-older")).toBeInTheDocument();
    expect(reads).toContain("conv-older");
    expect(useChatStore.getState().selectedAgentId).toBe("agent1");
  });

  it("switches to the named conversation even when its agent is already selected", async () => {
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv-current");
    server.use(
      http.get("*/agents/:conversationId", ({ params }) =>
        HttpResponse.json(snapshot(String(params.conversationId), "the older one")),
      ),
    );

    renderWithProviders(<ChatPanel />, {
      initialRoute: "/manage/chat?agentId=agent1&conversationId=conv-older",
    });

    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-older"));
  });
});
