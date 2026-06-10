import { describe, it, expect, beforeEach, beforeAll, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChatPanel } from "../chat-panel";
import { useChatStore } from "@/hooks/use-chat";
import { useDebugStore } from "@/hooks/use-debug-events";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("ChatPanel", () => {
  const mockWriteText = vi.fn();

  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    
    if (typeof navigator !== "undefined") {
      if (!navigator.clipboard) {
        Object.defineProperty(navigator, "clipboard", {
          value: { writeText: mockWriteText },
          writable: true,
          configurable: true,
        });
      } else {
        vi.spyOn(navigator.clipboard, "writeText").mockImplementation(mockWriteText);
      }
    }
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
    useDebugStore.getState().reset();
    mockWriteText.mockReset();
  });

  it("renders empty state initially when no agent is selected", () => {
    renderWithProviders(<ChatPanel />);
    expect(screen.getByText("Select an agent and start chatting!")).toBeInTheDocument();
  });

  it("loads and allows selecting deployed agents", async () => {
    const user = userEvent.setup();
    
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
            name: "Customer Support Bot",
            description: "Answers user questions",
          },
        ]);
      }),
      http.get("*/documentdescriptor/descriptors/agentstore/agents/agent1", () => {
        return HttpResponse.json({
          resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
          name: "Customer Support Bot",
          description: "Answers user questions",
        });
      }),
      http.get("*/deployment/production/agentstore/agents/agent1/version/1", () => {
        return HttpResponse.json({ status: "READY" });
      }),
      http.post("*/agents/agent1/start", () => {
        return HttpResponse.json(null, {
          status: 201,
          headers: {
            Location: "eddi://ai.labs.conversation/conversationstore/conversations/conv123",
          },
        });
      }),
      http.get("*/agents/conv123", () => {
        return HttpResponse.json({
          conversationSteps: [],
          conversationOutputs: [
            {
              output: [{ type: "text", text: "Welcome to support!" }],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatPanel />);

    const selector = screen.getByTestId("agent-selector");
    expect(selector).toBeInTheDocument();
    
    await user.click(selector);
    
    const option = await screen.findByText("Customer Support Bot");
    await user.click(option);

    await waitFor(() => {
      expect(useChatStore.getState().selectedAgentId).toBe("agent1");
      expect(useChatStore.getState().conversationId).toBe("conv123");
      expect(screen.getByText("Welcome to support!")).toBeInTheDocument();
    });
  });

  it("supports sending a normal text message and quick replies", async () => {
    const user = userEvent.setup();
    
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().setQuickReplies(["Yes", "No"]);

    server.use(
      http.post("*/agents/conv1", () => {
        return HttpResponse.json({
          conversationOutputs: [
            {
              output: [{ type: "text", text: "I processed your request" }],
              quickReplies: ["Yes", "No"],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatPanel />);

    const input = screen.getByTestId("chat-input");
    const sendBtn = screen.getByTestId("chat-send");
    expect(sendBtn).toBeInTheDocument();

    await user.type(input, "Hello bot");
    await user.click(sendBtn);

    await waitFor(() => {
      expect(screen.getByText("I processed your request")).toBeInTheDocument();
    });

    // Quick reply click
    const qrBtns = screen.getAllByTestId("quick-reply-btn");
    expect(qrBtns[0]).toHaveTextContent("Yes");
    await user.click(qrBtns[0]);

    await waitFor(() => {
      const messages = useChatStore.getState().messages;
      expect(messages.some((m) => m.role === "user" && m.content === "Yes")).toBe(true);
    });
  });

  it("toggles secret mode and sends a masked input", async () => {
    const user = userEvent.setup();
    
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/agents/conv1", () => {
        return HttpResponse.json({
          conversationOutputs: [
            {
              output: [{ type: "text", text: "Secret verified" }],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatPanel />);

    const toggle = screen.getByTestId("chat-secret-toggle");
    await user.click(toggle);

    // Now input is password type
    const input = screen.getByTestId("chat-input");
    expect(input).toHaveAttribute("type", "password");

    await user.type(input, "my-secret-key");
    const sendBtn = screen.getByTestId("chat-send");
    await user.click(sendBtn);

    // User message shown in chat should be masked
    expect(await screen.findByText("●●●●●●●●")).toBeInTheDocument();
  });

  it("supports file attachments uploading and triggers uploadAttachment", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/conversations/conv1/attachments", () => {
        return HttpResponse.json({ storageRef: "attachments-ref-123" });
      }),
      http.post("*/agents/conv1", () => {
        return HttpResponse.json({
          conversationOutputs: [],
        });
      })
    );

    renderWithProviders(<ChatPanel />);

    const attachBtn = screen.getByTestId("chat-attach-btn");
    expect(attachBtn).toBeInTheDocument();

    const fileInput = screen.getByTestId("chat-file-input");
    
    const file = new File(["dummy content"], "test-file.txt", { type: "text/plain" });
    await user.upload(fileInput, file);

    await waitFor(() => {
      expect(useChatStore.getState().messages.some(m => m.content.includes("attachments-ref-123"))).toBe(true);
    });
  });

  it("displays rerun button after error and runs rerun mutation", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().addMessage({
      id: "m1",
      role: "agent",
      content: "⚠️ Error: Something went wrong",
      timestamp: Date.now(),
    });

    server.use(
      http.post("*/agents/conv1/rerun", () => {
        return new HttpResponse(null, { status: 200 });
      }),
      http.get("*/agents/conv1", () => {
        return HttpResponse.json({
          conversationSteps: [
            {
              conversationStep: [
                { key: "input:initial", value: "hello" }
              ]
            }
          ],
          conversationOutputs: [
            {
              output: [{ type: "text", text: "Successfully rerun step!" }],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatPanel />);

    const rerunBtn = screen.getByTestId("rerun-btn");
    expect(rerunBtn).toBeInTheDocument();

    await user.click(rerunBtn);

    await waitFor(() => {
      expect(screen.getByText("Successfully rerun step!")).toBeInTheDocument();
    });
  });

  it("handles undo and redo conversation mutations", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    useChatStore.getState().setUndoRedo(true, true);

    server.use(
      http.post("*/agents/conv1/undo", () => {
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 1,
          conversationId: "conv1",
          conversationSteps: [],
          conversationOutputs: [],
          redoAvailable: true,
        });
      }),
      http.post("*/agents/conv1/redo", () => {
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 1,
          conversationId: "conv1",
          conversationSteps: [],
          conversationOutputs: [],
          redoAvailable: false,
        });
      })
    );

    renderWithProviders(<ChatPanel />);

    const undoBtn = screen.getByTestId("undo-btn");
    const redoBtn = screen.getByTestId("redo-btn");

    expect(undoBtn).toBeEnabled();
    expect(redoBtn).toBeEnabled();

    await user.click(undoBtn);
    await user.click(redoBtn);
  });

  it("auto-starts conversation if agentId query parameter is present", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent-query-1?version=1",
            name: "Auto Started Agent",
            description: "Loaded via query param",
          },
        ]);
      }),
      http.get("*/documentdescriptor/descriptors/agentstore/agents/agent-query-1", () => {
        return HttpResponse.json({
          resource: "eddi://ai.labs.agent/agentstore/agents/agent-query-1?version=1",
          name: "Auto Started Agent",
          description: "Loaded via query param",
        });
      }),
      http.get("*/deployment/production/agentstore/agents/agent-query-1/version/1", () => {
        return HttpResponse.json({ status: "READY" });
      }),
      http.post("*/agents/agent-query-1/start", () => {
        return HttpResponse.json(null, {
          status: 201,
          headers: {
            Location: "eddi://ai.labs.conversation/conversationstore/conversations/conv-query",
          },
        });
      }),
      http.get("*/agents/conv-query", () => {
        return HttpResponse.json({
          conversationSteps: [],
          conversationOutputs: [
            {
              output: [{ type: "text", text: "Auto hello!" }],
            },
          ],
        });
      })
    );

    renderWithProviders(<ChatPanel />, { initialRoute: "/?agentId=agent-query-1" });

    await waitFor(() => {
      expect(useChatStore.getState().selectedAgentId).toBe("agent-query-1");
      expect(useChatStore.getState().conversationId).toBe("conv-query");
      expect(screen.getByText("Auto hello!")).toBeInTheDocument();
    });
  });
});
