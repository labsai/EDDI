import { create } from "zustand";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  startConversation,
  readConversation,
  sendMessage,
  sendMessageWithContext,
  sendMessageStreaming,
  endConversation as endConversationApi,
  undoConversation as undoConversationApi,
  redoConversation as redoConversationApi,
  type ChatMessage,
  type SSEEvent,
} from "@/lib/api/chat";
import {
  getConversationDescriptors,
  type SimpleConversationMemorySnapshot,
  type InputField,
  extractInput,
  extractOutput,
  extractInputField,
  extractQuickReplies,
} from "@/lib/api/conversations";
import {
  getAgentDescriptors,
  getDeploymentStatus,
  type AgentDescriptor,
  parseResourceUri,
} from "@/lib/api/agents";

// --- Zustand Store ---

interface ChatState {
  messages: ChatMessage[];
  conversationId: string | null;
  selectedAgentId: string | null;
  selectedAgentName: string | null;
  isProcessing: boolean;
  isThinking: boolean;
  streamingEnabled: boolean;
  undoAvailable: boolean;
  redoAvailable: boolean;
  quickReplies: string[];
  /** Set when the backend requests a specific input field (e.g. password). */
  activeInputField: InputField | null;
  /** Set when the user toggles the 🔒 secret mode on the chat input. */
  isSecretMode: boolean;

  // Actions
  setSelectedAgent: (agentId: string | null, agentName: string | null) => void;
  setConversationId: (id: string | null) => void;
  addMessage: (message: ChatMessage) => void;
  appendToLastAgentMessage: (token: string) => void;
  finishStreaming: () => void;
  setProcessing: (v: boolean) => void;
  setThinking: (v: boolean) => void;
  toggleStreaming: () => void;
  clearMessages: () => void;
  setUndoRedo: (undo: boolean, redo: boolean) => void;
  setQuickReplies: (replies: string[]) => void;
  replaceMessages: (messages: ChatMessage[]) => void;
  setInputField: (field: InputField) => void;
  clearInputField: () => void;
  toggleSecretMode: () => void;
  reset: () => void;
}

const loadStreamingPref = (): boolean => {
  try {
    return localStorage.getItem("eddi-chat-streaming") !== "false";
  } catch {
    return true;
  }
};

export const useChatStore = create<ChatState>((set) => ({
  messages: [],
  conversationId: null,
  selectedAgentId: null,
  selectedAgentName: null,
  isProcessing: false,
  isThinking: false,
  streamingEnabled: loadStreamingPref(),
  undoAvailable: false,
  redoAvailable: false,
  quickReplies: [],
  activeInputField: null,
  isSecretMode: false,

  setSelectedAgent: (agentId, agentName) =>
    set({
      selectedAgentId: agentId,
      selectedAgentName: agentName,
      conversationId: null,
      messages: [],
    }),

  setConversationId: (id) => set({ conversationId: id }),

  addMessage: (message) =>
    set((s) => ({ messages: [...s.messages, message] })),

  appendToLastAgentMessage: (token) =>
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === "agent") {
        msgs[msgs.length - 1] = { ...last, content: last.content + token };
      }
      return { messages: msgs };
    }),

  finishStreaming: () =>
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === "agent") {
        msgs[msgs.length - 1] = { ...last, isStreaming: false };
      }
      return { messages: msgs, isProcessing: false };
    }),

  setProcessing: (v) => set({ isProcessing: v }),

  setThinking: (v) => set({ isThinking: v }),

  toggleStreaming: () =>
    set((s) => {
      const next = !s.streamingEnabled;
      try {
        localStorage.setItem("eddi-chat-streaming", String(next));
      } catch {
        /* noop */
      }
      return { streamingEnabled: next };
    }),

  clearMessages: () => set({ messages: [], conversationId: null, undoAvailable: false, redoAvailable: false, quickReplies: [], activeInputField: null, isSecretMode: false }),

  setUndoRedo: (undo, redo) => set({ undoAvailable: undo, redoAvailable: redo }),

  setQuickReplies: (replies) => set({ quickReplies: replies }),

  replaceMessages: (messages) => set({ messages }),

  setInputField: (field) => set({ activeInputField: field }),

  clearInputField: () => set({ activeInputField: null }),

  toggleSecretMode: () => set((s) => ({ isSecretMode: !s.isSecretMode })),

  reset: () =>
    set({
      messages: [],
      conversationId: null,
      selectedAgentId: null,
      selectedAgentName: null,
      isProcessing: false,
      isThinking: false,
      undoAvailable: false,
      redoAvailable: false,
      quickReplies: [],
      activeInputField: null,
      isSecretMode: false,
    }),
}));

// --- TanStack Query Hooks ---

const CHAT_KEY = ["chat"] as const;

/** Fetch agent descriptors and filter to only those that are deployed. */
export function useDeployedAgents() {
  return useQuery({
    queryKey: [...CHAT_KEY, "deployedAgents"],
    queryFn: async () => {
      const descriptors = await getAgentDescriptors(100, 0, "");
      // Deduplicate by name, keep latest version
      const grouped = new Map<
        string,
        AgentDescriptor & { id: string; version: number }
      >();
      for (const agent of descriptors) {
        const { id, version } = parseResourceUri(agent.resource);
        const existing = grouped.get(agent.name);
        if (!existing || version > existing.version) {
          grouped.set(agent.name, { ...agent, id, version });
        }
      }
      const agents = Array.from(grouped.values());

      // Check deployment status for each
      const deployed: (AgentDescriptor & { id: string; version: number })[] = [];
      for (const agent of agents) {
        try {
          const status = await getDeploymentStatus("production", agent.id, agent.version);
          if (status.status === "READY") {
            deployed.push(agent);
          }
        } catch {
          // skip agents whose status can't be fetched
        }
      }
      return deployed;
    },
    staleTime: 60_000,
  });
}

/** Start a new conversation with a agent, then GET to pick up welcome messages. */
export function useStartConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({ agentId }: { agentId: string }) => {
      const conversationId = await startConversation("production", agentId);
      store.getState().setConversationId(conversationId);

      // GET immediately to pick up any welcome message
      const snapshot = await readConversation(
        "production",
        agentId,
        conversationId,
        false
      );

      // Convert welcome steps to ChatMessages
      const outputs = snapshot.conversationOutputs ?? [];
      for (const output of outputs) {
        const text = extractOutput(output);
        if (text) {
          store.getState().addMessage({
            id: `welcome-${Date.now()}-${Math.random()}`,
            role: "agent",
            content: text,
            timestamp: Date.now(),
          });
        }
        // Extract quick replies from the last output
        const qr = extractQuickReplies(output);
        if (qr.length > 0) {
          store.getState().setQuickReplies(qr);
        }
      }

      return conversationId;
    },
  });
}

/** Send a message — auto-branches between streaming and non-streaming.
 *  Supports secret mode: masks user message and sends secretInput context. */
export function useSendMessage() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({ message, isSecret }: { message: string; isSecret?: boolean }) => {
      const state = store.getState();
      const { selectedAgentId, conversationId, streamingEnabled } = state;
      if (!selectedAgentId || !conversationId) {
        throw new Error("No active conversation");
      }

      // Add user message (masked if secret)
      state.addMessage({
        id: `user-${Date.now()}`,
        role: "user",
        content: isSecret ? "●●●●●●●●" : message,
        timestamp: Date.now(),
      });
      state.setProcessing(true);

      // Clear input field state after send
      if (isSecret) {
        state.clearInputField();
      }

      if (streamingEnabled) {
        // --- Streaming path ---
        state.addMessage({
          id: `agent-${Date.now()}`,
          role: "agent",
          content: "",
          timestamp: Date.now(),
          isStreaming: true,
        });

        const events = sendMessageStreaming(
          "production",
          selectedAgentId,
          conversationId,
          { input: message }
        );

        for await (const event of events) {
          handleSSEEvent(event, store);
        }
        state.finishStreaming();
      } else {
        // --- Non-streaming path — pass context for secret input ---
        // Add a placeholder typing indicator while waiting for the response
        const typingId = `agent-typing-${Date.now()}`;
        state.addMessage({
          id: typingId,
          role: "agent",
          content: "",
          timestamp: Date.now(),
          isStreaming: true,
        });

        let snapshot;
        if (isSecret) {
          snapshot = await sendMessageWithContext(
            "production",
            selectedAgentId,
            conversationId,
            {
              input: message,
              context: { secretInput: { type: "string", value: "true" } },
            }
          );
        } else {
          snapshot = await sendMessage(
            "production",
            selectedAgentId,
            conversationId,
            message
          );
        }

        // Extract agent output from the last conversationOutput
        const lastOutput = snapshot.conversationOutputs?.[
          (snapshot.conversationOutputs?.length ?? 1) - 1
        ];
        const output = extractOutput(lastOutput);

        // Replace the typing placeholder with the real response
        store.setState((s) => {
          const msgs = s.messages.filter((m) => m.id !== typingId);
          if (output) {
            msgs.push({
              id: `agent-${Date.now()}`,
              role: "agent",
              content: output,
              timestamp: Date.now(),
            });
          }
          return { messages: msgs };
        });

        // Check for input field requests (e.g. password)
        const inputField = extractInputField(lastOutput);
        if (inputField) {
          state.setInputField(inputField);
        }

        // Extract quick replies
        const qr = extractQuickReplies(lastOutput);
        state.setQuickReplies(qr);
        state.setProcessing(false);
      }
    },
    onError: () => {
      store.getState().setProcessing(false);
    },
  });
}

function handleSSEEvent(event: SSEEvent, store: typeof useChatStore) {
  switch (event.type) {
    case "token":
      store.getState().setThinking(false);
      store.getState().appendToLastAgentMessage(event.data);
      break;
    case "done":
      store.getState().finishStreaming();
      // Parse the snapshot from the done event to extract quickReplies
      // and conversation state (for structured JSON output mode).
      console.log('[QR-DEBUG] SSE done event.data:', event.data?.substring(0, 500));
      if (event.data) {
        try {
          const snapshot = JSON.parse(event.data);
          console.log('[QR-DEBUG] SSE done parsed outputs:', JSON.stringify(snapshot.conversationOutputs)?.substring(0, 500));
          if (snapshot.conversationOutputs?.length) {
            const lastOutput = snapshot.conversationOutputs[
              snapshot.conversationOutputs.length - 1
            ];
            const qr = extractQuickReplies(lastOutput);
            console.log('[QR-DEBUG] SSE extracted QR:', qr);
            store.getState().setQuickReplies(qr);
          }
        } catch (e) {
          console.error('[QR-DEBUG] SSE done parse error:', e);
        }
      }
      break;
    case "error":
      store
        .getState()
        .appendToLastAgentMessage(`\n\n⚠️ Error: ${event.data}`);
      store.getState().finishStreaming();
      break;
    case "task_start":
      store.getState().setThinking(true);
      break;
    case "task_complete":
      store.getState().setThinking(false);
      break;
  }
}

/** Helper: rebuild messages from a conversation snapshot */
function snapshotToMessages(snapshot: SimpleConversationMemorySnapshot): ChatMessage[] {
  const messages: ChatMessage[] = [];
  const outputs = snapshot.conversationOutputs ?? [];
  for (let i = 0; i < (snapshot.conversationSteps ?? []).length; i++) {
    const step = snapshot.conversationSteps[i];
    const input = step ? extractInput(step) : undefined;
    const output = extractOutput(outputs[i]);
    if (input) {
      messages.push({
        id: `user-${messages.length}-${Date.now()}`,
        role: "user",
        content: input,
        timestamp: Date.now(),
      });
    }
    if (output) {
      messages.push({
        id: `agent-${messages.length}-${Date.now()}`,
        role: "agent",
        content: output,
        timestamp: Date.now(),
      });
    }
  }
  return messages;
}

/** Fetch conversation history for the selected agent. */
export function useConversationHistory(agentId: string | null) {
  return useQuery({
    queryKey: [...CHAT_KEY, "history", agentId],
    queryFn: () => getConversationDescriptors(50, 0, "", agentId ?? ""),
    enabled: !!agentId,
    staleTime: 30_000,
  });
}

/** Load an existing conversation to resume it. */
export function useLoadConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({
      agentId,
      conversationId,
    }: {
      agentId: string;
      conversationId: string;
    }) => {
      store.getState().clearMessages();
      store.getState().setConversationId(conversationId);

      const snapshot = await readConversation(
        "production",
        agentId,
        conversationId,
        false
      );

      const messages = snapshotToMessages(snapshot);
      store.getState().replaceMessages(messages);
      store.getState().setUndoRedo(
        snapshot.conversationSteps.length > 0,
        snapshot.redoAvailable ?? false
      );

      return snapshot;
    },
  });
}

/** Undo the last conversation step. */
export function useUndoConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async () => {
      const { selectedAgentId, conversationId } = store.getState();
      if (!selectedAgentId || !conversationId) throw new Error("No active conversation");

      const snapshot = await undoConversationApi("production", selectedAgentId, conversationId);
      const messages = snapshotToMessages(snapshot);
      store.getState().replaceMessages(messages);
      store.getState().setUndoRedo(
        snapshot.conversationSteps.length > 0,
        snapshot.redoAvailable ?? false
      );
      return snapshot;
    },
  });
}

/** Redo a previously undone step. */
export function useRedoConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async () => {
      const { selectedAgentId, conversationId } = store.getState();
      if (!selectedAgentId || !conversationId) throw new Error("No active conversation");

      const snapshot = await redoConversationApi("production", selectedAgentId, conversationId);
      const messages = snapshotToMessages(snapshot);
      store.getState().replaceMessages(messages);
      store.getState().setUndoRedo(
        snapshot.conversationSteps.length > 0,
        snapshot.redoAvailable ?? false
      );
      return snapshot;
    },
  });
}

/** End the current conversation. */
export function useEndConversation() {
  const store = useChatStore;
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const conversationId = store.getState().conversationId;
      if (!conversationId) throw new Error("No active conversation");
      await endConversationApi(conversationId);
      store.getState().clearMessages();
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [...CHAT_KEY, "history"] });
    },
  });
}
