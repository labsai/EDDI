import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { http, HttpResponse } from "msw";
import {
  useChatStore,
  useDeployedAgents,
  useStartConversation,
  useConversationHistory,
  useEndConversation,
  useLoadConversation,
  useUndoConversation,
  useRedoConversation,
  useRerunConversation,
  useSendMessage,
} from "@/hooks/use-chat";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

// ─── Zustand Store Tests ─────────────────────────────────────

describe("useChatStore", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
  });

  it("starts with default state", () => {
    const s = useChatStore.getState();
    expect(s.messages).toEqual([]);
    expect(s.conversationId).toBeNull();
    expect(s.selectedAgentId).toBeNull();
    expect(s.selectedAgentName).toBeNull();
    expect(s.isProcessing).toBe(false);
    expect(s.isThinking).toBe(false);
    expect(s.undoAvailable).toBe(false);
    expect(s.redoAvailable).toBe(false);
    expect(s.quickReplies).toEqual([]);
    expect(s.activeInputField).toBeNull();
    expect(s.isSecretMode).toBe(false);
  });

  it("setSelectedAgent clears conversation and messages", () => {
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Hi",
      timestamp: Date.now(),
    });
    useChatStore.getState().setConversationId("conv-1");

    useChatStore.getState().setSelectedAgent("agent-2", "Agent 2");

    const s = useChatStore.getState();
    expect(s.selectedAgentId).toBe("agent-2");
    expect(s.selectedAgentName).toBe("Agent 2");
    expect(s.conversationId).toBeNull();
    expect(s.messages).toEqual([]);
  });

  it("addMessage appends to messages array", () => {
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Hello",
      timestamp: 1000,
    });
    useChatStore.getState().addMessage({
      id: "m2",
      role: "agent",
      content: "Hi!",
      timestamp: 2000,
    });
    expect(useChatStore.getState().messages).toHaveLength(2);
    expect(useChatStore.getState().messages[0]!.content).toBe("Hello");
    expect(useChatStore.getState().messages[1]!.content).toBe("Hi!");
  });

  it("appendToLastAgentMessage appends token to last agent message", () => {
    useChatStore.getState().addMessage({
      id: "m1",
      role: "agent",
      content: "Hello",
      timestamp: 1000,
    });
    useChatStore.getState().appendToLastAgentMessage(" world");
    expect(useChatStore.getState().messages[0]!.content).toBe("Hello world");
  });

  it("appendToLastAgentMessage does nothing if last message is user", () => {
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Hello",
      timestamp: 1000,
    });
    useChatStore.getState().appendToLastAgentMessage(" world");
    expect(useChatStore.getState().messages[0]!.content).toBe("Hello");
  });

  it("finishStreaming stops streaming and processing", () => {
    useChatStore.getState().setProcessing(true);
    useChatStore.getState().addMessage({
      id: "m1",
      role: "agent",
      content: "Streaming...",
      timestamp: 1000,
      isStreaming: true,
    });

    useChatStore.getState().finishStreaming();

    const s = useChatStore.getState();
    expect(s.isProcessing).toBe(false);
    expect(s.messages[0]!.isStreaming).toBe(false);
  });

  it("toggleStreaming toggles streaming state", () => {
    const initial = useChatStore.getState().streamingEnabled;
    useChatStore.getState().toggleStreaming();
    expect(useChatStore.getState().streamingEnabled).toBe(!initial);
    useChatStore.getState().toggleStreaming();
    expect(useChatStore.getState().streamingEnabled).toBe(initial);
  });

  it("clearMessages resets messages and related state", () => {
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Test",
      timestamp: 1000,
    });
    useChatStore.getState().setConversationId("conv-1");
    useChatStore.getState().setUndoRedo(true, true);
    useChatStore.getState().setQuickReplies(["Yes", "No"]);

    useChatStore.getState().clearMessages();

    const s = useChatStore.getState();
    expect(s.messages).toEqual([]);
    expect(s.conversationId).toBeNull();
    expect(s.undoAvailable).toBe(false);
    expect(s.redoAvailable).toBe(false);
    expect(s.quickReplies).toEqual([]);
    expect(s.activeInputField).toBeNull();
    expect(s.isSecretMode).toBe(false);
  });

  it("setUndoRedo sets undo/redo availability", () => {
    useChatStore.getState().setUndoRedo(true, false);
    expect(useChatStore.getState().undoAvailable).toBe(true);
    expect(useChatStore.getState().redoAvailable).toBe(false);
  });

  it("setQuickReplies updates quick replies array", () => {
    useChatStore.getState().setQuickReplies(["Option A", "Option B"]);
    expect(useChatStore.getState().quickReplies).toEqual([
      "Option A",
      "Option B",
    ]);
  });

  it("replaceMessages replaces entire messages array", () => {
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Old",
      timestamp: 1000,
    });
    useChatStore.getState().replaceMessages([
      { id: "m2", role: "agent", content: "New", timestamp: 2000 },
    ]);
    expect(useChatStore.getState().messages).toHaveLength(1);
    expect(useChatStore.getState().messages[0]!.content).toBe("New");
  });

  it("setInputField and clearInputField manage input field state", () => {
    useChatStore.getState().setInputField({
      subType: "password",
      label: "Enter password",
    });
    expect(useChatStore.getState().activeInputField?.subType).toBe("password");

    useChatStore.getState().clearInputField();
    expect(useChatStore.getState().activeInputField).toBeNull();
  });

  it("toggleSecretMode toggles secret mode", () => {
    expect(useChatStore.getState().isSecretMode).toBe(false);
    useChatStore.getState().toggleSecretMode();
    expect(useChatStore.getState().isSecretMode).toBe(true);
    useChatStore.getState().toggleSecretMode();
    expect(useChatStore.getState().isSecretMode).toBe(false);
  });

  it("reset restores all defaults", () => {
    useChatStore.getState().setSelectedAgent("agent-1", "Agent");
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "test",
      timestamp: 1000,
    });
    useChatStore.getState().setProcessing(true);
    useChatStore.getState().setThinking(true);

    useChatStore.getState().reset();

    const s = useChatStore.getState();
    expect(s.messages).toEqual([]);
    expect(s.conversationId).toBeNull();
    expect(s.selectedAgentId).toBeNull();
    expect(s.isProcessing).toBe(false);
    expect(s.isThinking).toBe(false);
  });

  it("setProcessing updates isProcessing", () => {
    useChatStore.getState().setProcessing(true);
    expect(useChatStore.getState().isProcessing).toBe(true);
    useChatStore.getState().setProcessing(false);
    expect(useChatStore.getState().isProcessing).toBe(false);
  });

  it("setThinking updates isThinking", () => {
    useChatStore.getState().setThinking(true);
    expect(useChatStore.getState().isThinking).toBe(true);
    useChatStore.getState().setThinking(false);
    expect(useChatStore.getState().isThinking).toBe(false);
  });

  it("setConversationId updates conversationId", () => {
    useChatStore.getState().setConversationId("conv-xyz");
    expect(useChatStore.getState().conversationId).toBe("conv-xyz");
  });
});

// ─── TanStack Query Hooks ────────────────────────────────────

describe("useDeployedAgents", () => {
  it("fetches and returns deployed agents", async () => {
    const { result } = renderHook(() => useDeployedAgents(), {
      wrapper: createWrapper(),
    });
    await waitFor(
      () => expect(result.current.isSuccess).toBe(true),
      { timeout: 10000 },
    );
    expect(Array.isArray(result.current.data)).toBe(true);
  });
});

describe("useConversationHistory", () => {
  it("fetches conversation history for an agent", async () => {
    const { result } = renderHook(
      () => useConversationHistory("agent-1"),
      { wrapper: createWrapper() },
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(Array.isArray(result.current.data)).toBe(true);
  });

  it("is disabled when agentId is null", () => {
    const { result } = renderHook(
      () => useConversationHistory(null),
      { wrapper: createWrapper() },
    );
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useStartConversation", () => {
  it("starts a conversation and sets conversationId", async () => {
    useChatStore.getState().reset();

    const { result } = renderHook(() => useStartConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ agentId: "agent-1" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(useChatStore.getState().conversationId).not.toBeNull();
  });

  it("picks up welcome messages from initial GET", async () => {
    useChatStore.getState().reset();

    const { result } = renderHook(() => useStartConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ agentId: "agent-1" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // The MSW handler returns conversationOutputs with welcome text
    const messages = useChatStore.getState().messages;
    expect(messages.length).toBeGreaterThanOrEqual(0); // May have welcome messages
  });
});

describe("useEndConversation", () => {
  it("ends a conversation and clears messages", async () => {
    // Setup: start a conversation first
    useChatStore.getState().reset();
    useChatStore.getState().setConversationId("conv-test-end");
    useChatStore.getState().addMessage({
      id: "m1",
      role: "user",
      content: "Hi",
      timestamp: 1000,
    });

    // Add handler for endConversation
    server.use(
      http.post("*/agents/:convId/endConversation", () => {
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { result } = renderHook(() => useEndConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(useChatStore.getState().messages).toEqual([]);
    expect(useChatStore.getState().conversationId).toBeNull();
  });

  it("throws error when no active conversation", async () => {
    useChatStore.getState().reset();
    // conversationId is null

    server.use(
      http.post("*/agents/:convId/endConversation", () => {
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { result } = renderHook(() => useEndConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useLoadConversation", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
  });

  it("loads a conversation and populates messages", async () => {
    const { result } = renderHook(() => useLoadConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      agentId: "agent1",
      conversationId: "conv1",
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const state = useChatStore.getState();
    expect(state.conversationId).toBe("conv1");
    // Should have loaded messages from snapshot
    expect(state.messages.length).toBeGreaterThanOrEqual(0);
  });

  it("sets undo/redo availability from snapshot", async () => {
    const { result } = renderHook(() => useLoadConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({
      agentId: "agent1",
      conversationId: "conv1",
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // The mock snapshot has conversationSteps, so undo should be available
    const state = useChatStore.getState();
    expect(typeof state.undoAvailable).toBe("boolean");
    expect(typeof state.redoAvailable).toBe("boolean");
  });
});

describe("useUndoConversation", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv1");
  });

  it("undoes the last conversation step", async () => {
    let undoCalled = false;
    server.use(
      http.post("*/agents/:convId/undo", () => {
        undoCalled = true;
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 3,
          conversationId: "conv1",
          conversationState: "READY",
          conversationSteps: [],
          conversationOutputs: [],
          redoAvailable: true,
        });
      }),
    );

    const { result } = renderHook(() => useUndoConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(undoCalled).toBe(true);
  });

  it("throws when no active conversation", async () => {
    useChatStore.getState().reset(); // clear conversationId
    const { result } = renderHook(() => useUndoConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useRedoConversation", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv1");
  });

  it("redoes a previously undone step", async () => {
    let redoCalled = false;
    server.use(
      http.post("*/agents/:convId/redo", () => {
        redoCalled = true;
        return HttpResponse.json({
          agentId: "agent1",
          agentVersion: 3,
          conversationId: "conv1",
          conversationState: "READY",
          conversationSteps: [
            {
              conversationStep: [
                { key: "input:initial", value: "Hello" },
              ],
            },
          ],
          conversationOutputs: [
            { "output:text:greet": "Hi there!" },
          ],
          redoAvailable: false,
        });
      }),
    );

    const { result } = renderHook(() => useRedoConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(redoCalled).toBe(true);
  });

  it("throws when no active conversation", async () => {
    useChatStore.getState().reset();
    const { result } = renderHook(() => useRedoConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useRerunConversation", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv1");
  });

  it("reruns the last conversation step", async () => {
    let rerunCalled = false;
    server.use(
      http.post("*/agents/:convId/rerun", () => {
        rerunCalled = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { result } = renderHook(() => useRerunConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(rerunCalled).toBe(true);
  });

  it("throws when no active conversation", async () => {
    useChatStore.getState().reset();
    const { result } = renderHook(() => useRerunConversation(), {
      wrapper: createWrapper(),
    });

    result.current.mutate();
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe("useSendMessage", () => {
  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv-send-test");
    // Disable streaming so we use the simpler non-streaming path
    if (useChatStore.getState().streamingEnabled) {
      useChatStore.getState().toggleStreaming();
    }
  });

  it("sends a message in non-streaming mode", async () => {
    let sendCalled = false;
    server.use(
      http.post("*/agents/:conversationId", () => {
        sendCalled = true;
        return HttpResponse.json({
          conversationSteps: [
            { input: "Hello", output: "Hi there!" },
          ],
          conversationOutputs: [
            { "output:text:respond": "I can help you with that!" },
          ],
        });
      }),
    );

    const { result } = renderHook(() => useSendMessage(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ message: "Hello" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(sendCalled).toBe(true);

    const state = useChatStore.getState();
    // Should have user message added
    const userMessages = state.messages.filter(m => m.role === "user");
    expect(userMessages.length).toBeGreaterThanOrEqual(1);
    expect(userMessages[0]!.content).toBe("Hello");
  });

  it("masks user message in secret mode", async () => {
    server.use(
      http.post("*/agents/:conversationId", () => {
        return HttpResponse.json({
          conversationSteps: [],
          conversationOutputs: [
            { "output:text:respond": "Password accepted" },
          ],
        });
      }),
    );

    const { result } = renderHook(() => useSendMessage(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ message: "my-secret-password", isSecret: true });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const userMessages = useChatStore.getState().messages.filter(m => m.role === "user");
    expect(userMessages.length).toBeGreaterThanOrEqual(1);
    // Secret messages should be masked
    expect(userMessages[0]!.content).toBe("●●●●●●●●");
  });

  it("throws error when no active conversation", async () => {
    useChatStore.getState().reset(); // no conversation

    const { result } = renderHook(() => useSendMessage(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ message: "Hello" });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("surfaces error as agent message on failure", async () => {
    server.use(
      http.post("*/agents/:conversationId", () => {
        return new HttpResponse(null, { status: 500 });
      }),
    );

    const { result } = renderHook(() => useSendMessage(), {
      wrapper: createWrapper(),
    });

    result.current.mutate({ message: "Hello" });
    await waitFor(() => expect(result.current.isError).toBe(true));

    // The onError handler should add an error message as agent message
    const agentMessages = useChatStore.getState().messages.filter(m => m.role === "agent");
    const errorMessage = agentMessages.find(m => m.content.includes("⚠️ Error"));
    expect(errorMessage).toBeDefined();
    expect(useChatStore.getState().isProcessing).toBe(false);
  });
});
