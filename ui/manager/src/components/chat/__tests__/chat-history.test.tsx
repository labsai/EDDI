import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatHistory } from "../chat-history";
import { useChatStore } from "@/hooks/use-chat";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("ChatHistory", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
  });

  it("renders nothing when open is false", () => {
    const { container } = renderWithProviders(
      <ChatHistory open={false} onNewConversation={() => {}} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders the history header and controls when open is true", async () => {
    useChatStore.getState().setSelectedAgent("agent-1", "Agent 1");
    renderWithProviders(<ChatHistory open={true} onNewConversation={() => {}} />);

    expect(screen.getByText("History")).toBeInTheDocument();
    expect(screen.getByTestId("new-conversation-btn")).toBeInTheDocument();
  });

  it("calls onNewConversation when clicking new conversation button", async () => {
    const user = userEvent.setup();
    const handleNew = vi.fn();
    useChatStore.getState().setSelectedAgent("agent-1", "Agent 1");

    renderWithProviders(<ChatHistory open={true} onNewConversation={handleNew} />);

    const newBtn = screen.getByTestId("new-conversation-btn");
    await user.click(newBtn);

    expect(handleNew).toHaveBeenCalled();
  });

  it("shows empty state when there are no conversations", async () => {
    useChatStore.getState().setSelectedAgent("agent-empty", "Agent Empty");

    server.use(
      http.get("*/conversationstore/conversations", () => {
        return HttpResponse.json([]);
      })
    );

    renderWithProviders(<ChatHistory open={true} onNewConversation={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText("No conversation history yet.")).toBeInTheDocument();
    });
  });

  it("displays conversation list when loaded", async () => {
    useChatStore.getState().setSelectedAgent("agent-1", "Agent 1");
    useChatStore.getState().setConversationId("conv2");

    server.use(
      http.get("*/conversationstore/conversations", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv1",
            conversationState: "READY",
            lastModifiedOn: new Date("2026-06-01").getTime(),
          },
          {
            resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv2",
            conversationState: "IN_PROGRESS",
            lastModifiedOn: new Date("2026-06-02").getTime(),
          },
        ]);
      })
    );

    renderWithProviders(<ChatHistory open={true} onNewConversation={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText(/conv1/)).toBeInTheDocument();
      expect(screen.getByText(/conv2/)).toBeInTheDocument();
    });

    // Check states
    expect(screen.getByText("READY")).toBeInTheDocument();
    expect(screen.getByText("IN_PROGRESS")).toBeInTheDocument();
  });

  it("triggers loadConversation when clicking a conversation item", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent-1", "Agent 1");

    server.use(
      http.get("*/conversationstore/conversations", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv1",
            conversationState: "READY",
            lastModifiedOn: Date.now(),
          },
        ]);
      }),
      http.get("*/agents/production/agent-1/conv1", () => {
        return HttpResponse.json({
          agentId: "agent-1",
          agentVersion: 1,
          conversationId: "conv1",
          conversationState: "READY",
          environment: "production",
          conversationSteps: [],
        });
      })
    );

    renderWithProviders(<ChatHistory open={true} onNewConversation={() => {}} />);

    const convBtn = await screen.findByText(/conv1/);
    await user.click(convBtn);

    await waitFor(() => {
      expect(useChatStore.getState().conversationId).toBe("conv1");
    });
  });
});
