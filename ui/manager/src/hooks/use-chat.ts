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
  type DeploymentStatus,
  parseResourceUri,
} from "@/lib/api/agents";
import { useDebugStore } from "@/hooks/use-debug-events";

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
      const descriptors = await getAgentDescriptors(500, 0, "");
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

      // Check deployment status in parallel (avoids N+1 sequential requests)
      const results = await Promise.allSettled(
        agents.map(async (agent) => {
          const status = await getDeploymentStatus("production", agent.id, agent.version);
          return { agent, status };
        })
      );

      return results
        .filter(
          (r): r is PromiseFulfilledResult<{ agent: typeof agents[number]; status: DeploymentStatus }> =>
            r.status === "fulfilled" && r.value.status.status === "READY"
        )
        .map((r) => r.value.agent);
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
      // Clear stale quick replies immediately so old buttons don't flash
      state.setQuickReplies([]);

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

        // Use AbortController so we can abort the underlying fetch when
        // the "done" event arrives.  The Vite dev-proxy (and some
        // production proxies) may not forward the SSE connection-close
        // signal, which means the for-await loop on the ReadableStream
        // reader never terminates — the mutationFn never returns and
        // TanStack Query blocks all subsequent .mutate() calls.
        const abort = new AbortController();

        const events = sendMessageStreaming(
          "production",
          selectedAgentId,
          conversationId,
          { input: message },
          abort.signal,
        );

        try {
          for await (const event of events) {
            const isDone = handleSSEEvent(event, store);
            if (isDone) {
              // Stream is logically complete — abort the fetch so the
              // reader.read() promise resolves immediately and the
              // mutation can finish.
              abort.abort();
              break;
            }
          }
        } catch (e) {
          // AbortError is expected when we abort after "done"
          if (e instanceof DOMException && e.name === "AbortError") {
            // expected — swallow
          } else {
            throw e;
          }
        }
        // Safety-net: if the stream ended without a done event
        // (e.g. connection drop), finalize it here.
        if (store.getState().isProcessing) {
          store.getState().finishStreaming();
        }
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
    onError: (error) => {
      const state = store.getState();
      // Surface the error as a visible agent message so it's not silently
      // swallowed (the user would otherwise see processing start then stop
      // with no feedback).
      const errorMsg =
        error && typeof error === "object" && "message" in error
          ? String((error as { message: string }).message)
          : String(error);
      state.addMessage({
        id: `agent-error-${Date.now()}`,
        role: "agent",
        content: `\n\n⚠️ Error: ${errorMsg}`,
        timestamp: Date.now(),
      });
      state.setProcessing(false);
      state.setQuickReplies([]);
    },
  });
}

/**
 * Process a single SSE event from the streaming response.
 * Returns `true` when the stream is logically complete ("done" or "error")
 * so the caller can break out of the for-await loop.
 */
function handleSSEEvent(event: SSEEvent, store: typeof useChatStore): boolean {
  const debug = useDebugStore.getState();

  switch (event.type) {
    case "token":
      store.getState().setThinking(false);
      store.getState().appendToLastAgentMessage(event.data);
      return false;
    case "done": {
      // Finalize the debug turn so pipeline trace can display it
      debug.finalizeTurn();
      // Parse the snapshot from the done event to extract quickReplies
      // BEFORE calling finishStreaming (which sets isProcessing=false)
      // so that stale quick reply buttons never flash.
      let newQuickReplies: string[] = [];
      if (event.data) {
        try {
          const snapshot = JSON.parse(event.data);
          if (snapshot.conversationOutputs?.length) {
            const lastOutput = snapshot.conversationOutputs[
              snapshot.conversationOutputs.length - 1
            ];
            newQuickReplies = extractQuickReplies(lastOutput);
          }
        } catch {
          // Ignore parse errors — done event data may be empty
        }
      }
      store.getState().setQuickReplies(newQuickReplies);
      store.getState().finishStreaming();
      return true;
    }
    case "error":
      store
        .getState()
        .appendToLastAgentMessage(`\n\n⚠️ Error: ${event.data}`);
      store.getState().finishStreaming();
      debug.finalizeTurn();
      return true;
    case "task_start": {
      store.getState().setThinking(true);
      // Parse event data for structured pipeline info
      let taskId = "unknown";
      let taskType = "unknown";
      let index = 0;
      try {
        const parsed = JSON.parse(event.data);
        taskId = parsed.taskId ?? parsed.id ?? "unknown";
        taskType = parsed.taskType ?? parsed.type ?? "unknown";
        index = parsed.index ?? 0;
      } catch {
        // plain-text event data — use as taskType
        taskType = event.data || "unknown";
      }
      debug.addEvent({
        type: "task_start",
        taskId,
        taskType,
        index,
        timestamp: Date.now(),
      });
      return false;
    }
    case "task_complete": {
      store.getState().setThinking(false);
      let taskId = "unknown";
      let taskType = "unknown";
      let index = 0;
      let durationMs: number | undefined;
      let actions: string[] | undefined;
      let confidence: number | undefined;
      try {
        const parsed = JSON.parse(event.data);
        taskId = parsed.taskId ?? parsed.id ?? "unknown";
        taskType = parsed.taskType ?? parsed.type ?? "unknown";
        index = parsed.index ?? 0;
        durationMs = parsed.durationMs ?? parsed.duration;
        actions = parsed.actions;
        confidence = parsed.confidence;
      } catch {
        taskType = event.data || "unknown";
      }
      debug.addEvent({
        type: "task_complete",
        taskId,
        taskType,
        index,
        durationMs,
        actions,
        confidence,
        timestamp: Date.now(),
      });
      return false;
    }
    default:
      return false;
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
