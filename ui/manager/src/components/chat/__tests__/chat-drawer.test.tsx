import { describe, it, expect, beforeEach, beforeAll, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatDrawer } from "../chat-drawer";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore } from "@/hooks/use-chat";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("ChatDrawer", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatDrawerStore.setState({
      isOpen: false,
      agentId: null,
      agentName: null,
      step: "idle",
      errorMessage: null,
    });
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
  });

  it("renders a closed container when isOpen is false", () => {
    renderWithProviders(<ChatDrawer />);
    const container = screen.getByTestId("chat-drawer");
    expect(container).toHaveClass("w-0");
    expect(container).toHaveClass("opacity-0");
    expect(container).toBeEmptyDOMElement();
  });

  it("renders header and agent info when open", async () => {
    const user = userEvent.setup();
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "idle",
    });

    renderWithProviders(<ChatDrawer />);
    expect(screen.getByText("Agent One")).toBeInTheDocument();
    
    // Close button triggers store close
    const closeBtn = screen.getByTestId("drawer-close");
    await user.click(closeBtn);
    expect(useChatDrawerStore.getState().isOpen).toBe(false);
  });

  it("displays progress steps during deploy pipeline", () => {
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "deploying",
    });

    renderWithProviders(<ChatDrawer />);
    expect(screen.getByText("Deploying agent…")).toBeInTheDocument();
    expect(screen.getByText("Saving changes…")).toBeInTheDocument();
  });

  it("shows error step and supports retry", async () => {
    const user = userEvent.setup();
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "error",
      errorMessage: "Deploy failed catastrophically",
    });

    renderWithProviders(<ChatDrawer />);
    expect(screen.getByText("Deploy failed catastrophically")).toBeInTheDocument();

    const retryBtn = screen.getByTestId("drawer-retry");
    await user.click(retryBtn);

    expect(useChatDrawerStore.getState().step).toBe("idle");
    expect(useChatDrawerStore.getState().isOpen).toBe(false);
  });

  it("shows ready/idle empty state", () => {
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "ready",
    });

    renderWithProviders(<ChatDrawer />);
    expect(screen.getByText("Ready — type a message to test")).toBeInTheDocument();
  });

  it("shows active conversation messages, thinking state, and quick replies", async () => {
    const user = userEvent.setup();
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "ready",
    });
    
    useChatStore.getState().setSelectedAgent("agent-1", "Agent One");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Hello agent",
      timestamp: Date.now(),
    });
    useChatStore.getState().addMessage({
      id: "m2",
      role: "agent",
      content: "Hello human",
      timestamp: Date.now(),
    });
    useChatStore.getState().setThinking(true);
    useChatStore.getState().setQuickReplies(["Reply Option 1", "Reply Option 2"]);

    server.use(
      http.post("*/agents/conv1", async () => {
        await new Promise((resolve) => setTimeout(resolve, 200));
        return HttpResponse.json({
          conversationOutputs: [
            {
              output: [{ type: "text", text: "Mock response" }],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatDrawer />);

    expect(screen.getByText("Hello agent")).toBeInTheDocument();
    expect(screen.getByText("Hello human")).toBeInTheDocument();
    expect(screen.getByText("Thinking...")).toBeInTheDocument();

    // Check quick replies
    const qrBtns = screen.getAllByTestId("drawer-quick-reply");
    expect(qrBtns).toHaveLength(2);
    expect(qrBtns[0]).toHaveTextContent("Reply Option 1");

    // Click quick reply to trigger mutation
    await user.click(qrBtns[0]);
    await waitFor(() => {
      expect(useChatStore.getState().isProcessing).toBe(true);
    });
    await waitFor(() => {
      expect(useChatStore.getState().isProcessing).toBe(false);
    });
  });

  it("allows typing and sending message", async () => {
    const user = userEvent.setup();
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "ready",
    });

    useChatStore.getState().setSelectedAgent("agent-1", "Agent One");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/agents/conv1", async () => {
        await new Promise((resolve) => setTimeout(resolve, 200));
        return HttpResponse.json({
          conversationOutputs: [
            {
              output: [{ type: "text", text: "I received your message" }],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatDrawer />);

    const input = screen.getByTestId("drawer-chat-input");
    const sendBtn = screen.getByTestId("drawer-chat-send");

    expect(sendBtn).toBeDisabled();

    await user.type(input, "Ping");
    expect(sendBtn).toBeEnabled();

    await user.click(sendBtn);

    // Should clear input and start processing
    expect(input).toHaveValue("");
    await waitFor(() => {
      expect(useChatStore.getState().isProcessing).toBe(true);
    });
    await waitFor(() => {
      expect(useChatStore.getState().isProcessing).toBe(false);
    });
  });

  it("resets conversation and starts a new one on button click", async () => {
    const user = userEvent.setup();
    useChatDrawerStore.setState({
      isOpen: true,
      agentId: "agent-1",
      agentName: "Agent One",
      step: "ready",
    });

    useChatStore.getState().setSelectedAgent("agent-1", "Agent One");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Hello agent",
      timestamp: Date.now(),
    });

    server.use(
      http.post("*/agents/agent-1/start", () => {
        return HttpResponse.json(null, {
          status: 201,
          headers: {
            Location: "eddi://ai.labs.conversation/conversationstore/conversations/conv-new",
          },
        });
      }),
      http.get("*/agents/conv-new", () => {
        return HttpResponse.json({
          conversationSteps: [],
          conversationOutputs: [],
        });
      })
    );

    renderWithProviders(<ChatDrawer />);

    const newBtn = screen.getByTestId("drawer-new-conversation");
    await user.click(newBtn);

    await waitFor(() => {
      expect(useChatDrawerStore.getState().step).toBe("ready");
      expect(useChatStore.getState().conversationId).toBe("conv-new");
      expect(useChatStore.getState().messages).toHaveLength(0);
    });
  });
});
