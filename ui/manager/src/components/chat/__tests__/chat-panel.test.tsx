import { describe, it, expect, beforeEach, beforeAll, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
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

  /** Handlers every agent-selection test needs: one deployed agent to pick. */
  function useDeployedAgent1() {
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
    );
  }

  async function selectCustomerSupportBot() {
    const user = userEvent.setup();
    renderWithProviders(<ChatPanel />);

    const selector = screen.getByTestId("agent-selector");
    expect(selector).toBeInTheDocument();

    await user.click(selector);
    const option = await screen.findByText("Customer Support Bot");
    await user.click(option);
  }

  // Picking an agent reopens where the user left off. Starting a fresh
  // conversation on every open used to litter history with empty entries and
  // silently drop the thread the user was in.
  it("selecting an agent reopens its most recent conversation", async () => {
    useDeployedAgent1();
    let started = false;
    server.use(
      http.post("*/agents/agent1/start", () => {
        started = true;
        return HttpResponse.json(null, {
          status: 201,
          headers: {
            Location: "eddi://ai.labs.conversation/conversationstore/conversations/conv123",
          },
        });
      }),
      // conv1 is the mock's most recent READY conversation for agent1.
      http.get("*/agents/conv1", () => {
        return HttpResponse.json({
          conversationSteps: [{ conversationStep: [{ key: "input:initial", value: "Earlier question" }] }],
          conversationOutputs: [{ output: [{ type: "text", text: "Earlier answer" }] }],
        });
      }),
    );

    await selectCustomerSupportBot();

    await waitFor(() => {
      expect(useChatStore.getState().selectedAgentId).toBe("agent1");
      expect(useChatStore.getState().conversationId).toBe("conv1");
    });
    expect(await screen.findByText("Earlier answer")).toBeInTheDocument();
    expect(started).toBe(false);
  });

  it("selecting an agent with nothing resumable starts a new conversation", async () => {
    useDeployedAgent1();
    server.use(
      // No conversation history for this agent at all.
      http.get("*/conversationstore/conversations", () => HttpResponse.json([])),
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
          conversationOutputs: [{ output: [{ type: "text", text: "Welcome to support!" }] }],
        });
      }),
    );

    await selectCustomerSupportBot();

    await waitFor(() => {
      expect(useChatStore.getState().selectedAgentId).toBe("agent1");
      expect(useChatStore.getState().conversationId).toBe("conv123");
      expect(screen.getByText("Welcome to support!")).toBeInTheDocument();
    });
  });

  it("falls back to a new conversation when the resumable one cannot be read", async () => {
    useDeployedAgent1();
    server.use(
      http.get("*/agents/conv1", () => HttpResponse.json({ error: "gone" }, { status: 404 })),
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
          conversationOutputs: [{ output: [{ type: "text", text: "Welcome to support!" }] }],
        });
      }),
    );

    await selectCustomerSupportBot();

    await waitFor(() => {
      expect(useChatStore.getState().conversationId).toBe("conv123");
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
    await user.click(qrBtns[0]!);

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

  it("dropping a file on the chat area stages it as an attachment", async () => {
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");
    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: "drop-ref-1", fileName: "dropped.txt", mimeType: "text/plain", sizeBytes: 3, forwardableInline: true },
          { status: 201 },
        ),
      ),
    );
    renderWithProviders(<ChatPanel />);

    const zone = screen.getByTestId("chat-input").closest(".relative")!;
    const dataTransfer = {
      files: [new File(["abc"], "dropped.txt", { type: "text/plain" })],
      types: ["Files"],
    };
    fireEvent.dragEnter(zone, { dataTransfer });
    expect(screen.getByTestId("file-drop-overlay")).toBeInTheDocument();
    fireEvent.drop(zone, { dataTransfer });

    expect(await screen.findByTestId("attachment-chip")).toBeInTheDocument();
    expect(screen.queryByTestId("file-drop-overlay")).not.toBeInTheDocument();
  });

  it("blocks attachments in secret mode — disables attach, drops staged files, never forwards", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    let sentBody:
      | { input?: string; context?: Record<string, { value?: { storageRef?: string } }> }
      | null = null;
    let deleteCalled = false;
    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: "ref-secret", fileName: "secret.txt", mimeType: "text/plain", sizeBytes: 3, forwardableInline: true },
          { status: 201 },
        ),
      ),
      http.delete("*/conversations/conv1/attachments/:storageRef", () => {
        deleteCalled = true;
        return HttpResponse.json({ deleted: true });
      }),
      http.post("*/agents/conv1", async ({ request }) => {
        sentBody = (await request.json()) as typeof sentBody;
        return HttpResponse.json({ conversationOutputs: [] });
      }),
    );

    renderWithProviders(<ChatPanel />);

    // Stage a file and wait for the upload to settle (remove button only shows
    // once status leaves "uploading"), then flip secret mode on.
    await user.upload(
      screen.getByTestId("chat-file-input"),
      new File(["abc"], "secret.txt", { type: "text/plain" }),
    );
    await screen.findByTestId("attachment-remove");
    await user.click(screen.getByTestId("chat-secret-toggle"));

    // Attach is disabled and the staged chip is dropped (its blob deleted).
    expect(screen.getByTestId("chat-attach-btn")).toBeDisabled();
    await waitFor(() =>
      expect(screen.queryByTestId("attachment-chip")).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(deleteCalled).toBe(true));

    // Send the masked secret — no attachment_* context, no chip in the bubble.
    await user.type(screen.getByTestId("chat-input"), "my-secret");
    await user.click(screen.getByTestId("chat-send"));

    expect(await screen.findByText("●●●●●●●●")).toBeInTheDocument();
    // The secret turn is sent with the secret flag but NO attachment_* context.
    await waitFor(() => {
      expect(sentBody?.context?.secretInput).toBeTruthy();
      expect(sentBody?.context?.attachment_0).toBeUndefined();
    });
    expect(screen.queryByTestId("message-attachments")).not.toBeInTheDocument();
  });

  it("uploads a picked file as a pending attachment, not an inline text message", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          {
            storageRef: "attachments-ref-123",
            fileName: "test-file.txt",
            mimeType: "text/plain",
            sizeBytes: 13,
            forwardableInline: true,
          },
          { status: 201 },
        ),
      ),
    );

    renderWithProviders(<ChatPanel />);

    const fileInput = screen.getByTestId("chat-file-input");
    const file = new File(["dummy content"], "test-file.txt", { type: "text/plain" });
    await user.upload(fileInput, file);

    // A pending chip appears — the file is NOT sent as inline "[ref:...]" text.
    expect(await screen.findByTestId("attachment-chip")).toBeInTheDocument();
    // Uploading must add NO chat message at all (regression guard: it used to
    // push "📎 file [ref:...]" as inline text the backend never parsed). The
    // chip lives in local component state, not the conversation.
    expect(useChatStore.getState().messages).toHaveLength(0);
  });

  it("forwards an uploaded attachment as attachment_* context when sent", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    let sentBody:
      | { input?: string; context?: Record<string, { value?: { storageRef?: string } }> }
      | null = null;

    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          {
            storageRef: "attachments-ref-123",
            fileName: "test-file.txt",
            mimeType: "text/plain",
            sizeBytes: 13,
            forwardableInline: true,
          },
          { status: 201 },
        ),
      ),
      http.post("*/agents/conv1", async ({ request }) => {
        sentBody = (await request.json()) as typeof sentBody;
        return HttpResponse.json({ conversationOutputs: [] });
      }),
    );

    renderWithProviders(<ChatPanel />);

    const fileInput = screen.getByTestId("chat-file-input");
    const file = new File(["dummy content"], "test-file.txt", { type: "text/plain" });
    await user.upload(fileInput, file);
    await screen.findByTestId("attachment-chip");

    await user.type(screen.getByTestId("chat-input"), "look at this");
    await user.click(screen.getByTestId("chat-send"));

    await waitFor(() => {
      expect(sentBody?.context?.attachment_0?.value?.storageRef).toBe("attachments-ref-123");
    });
    // The user bubble renders the sent attachment.
    expect(await screen.findByTestId("message-attachments")).toBeInTheDocument();
  });

  it("allows an attachment-only turn (empty text) and forwards it as context", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    let sentBody:
      | { input?: string; context?: Record<string, { value?: { storageRef?: string } }> }
      | null = null;
    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: "ref-only", fileName: "a.txt", mimeType: "text/plain", sizeBytes: 5, forwardableInline: true },
          { status: 201 },
        ),
      ),
      http.post("*/agents/conv1", async ({ request }) => {
        sentBody = (await request.json()) as typeof sentBody;
        return HttpResponse.json({ conversationOutputs: [] });
      }),
    );

    renderWithProviders(<ChatPanel />);
    await user.upload(screen.getByTestId("chat-file-input"), new File(["hello"], "a.txt", { type: "text/plain" }));
    await screen.findByTestId("attachment-chip");

    // No text typed — send is enabled purely by the ready attachment.
    await user.click(screen.getByTestId("chat-send"));

    await waitFor(() => {
      expect(sentBody?.context?.attachment_0?.value?.storageRef).toBe("ref-only");
      expect(sentBody?.input).toBe("");
    });
  });

  it("shows an error chip when an upload is rejected", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json({ error: "MIME type not allowed", code: "ATTACHMENT_REJECTED" }, { status: 400 }),
      ),
    );

    renderWithProviders(<ChatPanel />);
    await user.upload(screen.getByTestId("chat-file-input"), new File(["x"], "bad.exe", { type: "text/plain" }));

    expect(await screen.findByText("MIME type not allowed")).toBeInTheDocument();
  });

  it("enforces the per-turn attachment cap in the UI", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: `ref-${Math.random()}`, fileName: "f.txt", mimeType: "text/plain", sizeBytes: 1, forwardableInline: true },
          { status: 201 },
        ),
      ),
    );

    renderWithProviders(<ChatPanel />);
    const files = Array.from({ length: 6 }, (_, i) => new File(["x"], `f${i}.txt`, { type: "text/plain" }));
    await user.upload(screen.getByTestId("chat-file-input"), files);

    await waitFor(() => expect(screen.getAllByTestId("attachment-chip")).toHaveLength(5));
  });

  it("removes a chip and deletes the blob server-side", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    let deleteCalled = false;
    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: "ref-del", fileName: "a.txt", mimeType: "text/plain", sizeBytes: 1, forwardableInline: true },
          { status: 201 },
        ),
      ),
      http.delete("*/conversations/conv1/attachments/:storageRef", () => {
        deleteCalled = true;
        return HttpResponse.json({ deleted: true });
      }),
    );

    renderWithProviders(<ChatPanel />);
    await user.upload(screen.getByTestId("chat-file-input"), new File(["x"], "a.txt", { type: "text/plain" }));
    await screen.findByTestId("attachment-chip");
    await waitFor(() => expect(screen.getByTestId("attachment-remove")).toBeInTheDocument());

    await user.click(screen.getByTestId("attachment-remove"));

    await waitFor(() => expect(screen.queryByTestId("attachment-chip")).not.toBeInTheDocument());
    await waitFor(() => expect(deleteCalled).toBe(true));
  });

  it("renders an image thumbnail chip for image uploads", async () => {
    const user = userEvent.setup();
    const origCreate = URL.createObjectURL;
    URL.createObjectURL = vi.fn(() => "blob:mock");
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: "ref-img", fileName: "pic.png", mimeType: "image/png", sizeBytes: 9, forwardableInline: true },
          { status: 201 },
        ),
      ),
    );

    try {
      renderWithProviders(<ChatPanel />);
      await user.upload(screen.getByTestId("chat-file-input"), new File(["x"], "pic.png", { type: "image/png" }));
      const chip = await screen.findByTestId("attachment-chip");
      const img = chip.querySelector("img");
      expect(img).toHaveAttribute("src", "blob:mock");
    } finally {
      URL.createObjectURL = origCreate;
    }
  });

  it("warns that an oversized attachment was stored but not sent to the model", async () => {
    const user = userEvent.setup();
    useChatStore.getState().setSelectedAgent("agent1", "Test Agent");
    useChatStore.getState().setConversationId("conv1");

    server.use(
      http.post("*/conversations/conv1/attachments", () =>
        HttpResponse.json(
          { storageRef: "ref-big", fileName: "big.txt", mimeType: "text/plain", sizeBytes: 15000000, forwardableInline: false },
          { status: 201 },
        ),
      ),
      http.post("*/agents/conv1", () => HttpResponse.json({ conversationOutputs: [] })),
    );

    renderWithProviders(<ChatPanel />);
    await user.upload(screen.getByTestId("chat-file-input"), new File(["x"], "big.txt", { type: "text/plain" }));
    await screen.findByTestId("attachment-chip");
    await user.click(screen.getByTestId("chat-send"));

    // The sent user bubble flags that the file was not forwarded to the model.
    expect(await screen.findByTestId("attachment-not-forwarded")).toBeInTheDocument();
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
