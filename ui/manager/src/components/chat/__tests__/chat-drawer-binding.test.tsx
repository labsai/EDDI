import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatDrawer } from "../chat-drawer";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore } from "@/hooks/use-chat";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * The drawer shares its store with the main chat panel. It must only ever show
 * and send into a conversation of the agent it was opened for, and it must
 * render the masked field an agent asks for (it never did).
 */
describe("ChatDrawer — only this agent's conversation, and the secret field", () => {
  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
  });

  it("does not show or send into another agent's conversation still held by the store", () => {
    // The main chat left agent A's conversation in the shared store; the drawer
    // is then opened for agent B without starting anything (agent not live).
    useChatStore.getState().setSelectedAgent("agent-a", "Agent A");
    useChatStore.getState().setConversationId("conv-a");
    useChatStore.getState().addMessage({
      id: "a1",
      role: "agent",
      content: "A's private answer",
      timestamp: Date.now(),
    });
    useChatStore.getState().setQuickReplies(["A's button"]);
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-b",
      agentName: "Agent B",
      step: "idle",
      errorMessage: null,
    });

    renderWithProviders(<ChatDrawer />);

    expect(screen.queryByText("A's private answer")).not.toBeInTheDocument();
    expect(screen.queryByTestId("drawer-quick-reply")).not.toBeInTheDocument();
    expect(screen.queryByTestId("drawer-chat-input")).not.toBeInTheDocument();
  });

  it("starts a new conversation for the drawer's agent and environment, not the store's", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent-a", "Agent A");
    useChatStore.getState().setConversationId("conv-a");
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-b",
      agentName: "Agent B",
      environment: "test",
      step: "ready",
      errorMessage: null,
    });
    let startedFor: string | null = null;
    let startedIn: string | null = null;
    server.use(
      http.post("*/agents/:agentId/start", ({ params, request }) => {
        startedFor = String(params.agentId);
        startedIn = new URL(request.url).searchParams.get("environment");
        return HttpResponse.json({ location: "/agents/conv-b" });
      }),
      http.get("*/agents/conv-b", () =>
        HttpResponse.json({ conversationState: "READY", conversationSteps: [], conversationOutputs: [] }),
      ),
    );

    renderWithProviders(<ChatDrawer />);
    await user.click(screen.getByTestId("drawer-new-conversation"));

    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-b"));
    expect(startedFor).toBe("agent-b");
    expect(startedIn).toBe("test");
    expect(useChatStore.getState().selectedAgentId).toBe("agent-b");
  });

  it("renders the masked field the agent asked for and sends it as a secret turn", async () => {
    const user = userEvent.setup();
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "ready",
      errorMessage: null,
    });
    useChatStore.getState().setSelectedAgent("agent-1", "Agent One");
    useChatStore.getState().setConversationId("conv-s");
    useChatStore.getState().setInputField({ subType: "password", label: "API Key" });
    let sentBody: { input?: string; context?: Record<string, unknown> } | null = null;
    server.use(
      http.post("*/agents/conv-s", async ({ request }) => {
        sentBody = (await request.json()) as typeof sentBody;
        return HttpResponse.json({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: "Key stored." }] }],
        });
      }),
    );

    renderWithProviders(<ChatDrawer />);

    expect(screen.queryByTestId("drawer-chat-input")).not.toBeInTheDocument();
    const field = screen.getByTestId("secret-input-field");
    expect(field).toHaveAttribute("type", "password");

    await user.type(field, "sk-drawer-secret");
    await user.click(screen.getByTestId("secret-input-send"));

    await waitFor(() => expect(sentBody).not.toBeNull());
    expect(sentBody!.input).toBe("sk-drawer-secret");
    expect(sentBody!.context).toEqual(
      expect.objectContaining({ secretInput: { type: "string", value: "true" } }),
    );
    // The field gives way to the normal input, and the bubble is masked.
    await waitFor(() => expect(screen.getByTestId("drawer-chat-input")).toBeInTheDocument());
    expect(screen.queryByText("sk-drawer-secret")).not.toBeInTheDocument();
  });
});
