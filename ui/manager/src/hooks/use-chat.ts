import { create } from "zustand";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  startConversation,
  readConversation,
  sendMessage,
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
  extractInput,
  extractOutput,
  extractQuickReplies,
} from "@/lib/api/conversations";
import {
  getBotDescriptors,
  getDeploymentStatus,
  type BotDescriptor,
  parseResourceUri,
} from "@/lib/api/bots";

// --- Zustand Store ---

interface ChatState {
  messages: ChatMessage[];
  conversationId: string | null;
  selectedBotId: string | null;
  selectedBotName: string | null;
  isProcessing: boolean;
  isThinking: boolean;
  streamingEnabled: boolean;
  undoAvailable: boolean;
  redoAvailable: boolean;
  quickReplies: string[];

  // Actions
  setSelectedBot: (botId: string | null, botName: string | null) => void;
  setConversationId: (id: string | null) => void;
  addMessage: (message: ChatMessage) => void;
  appendToLastBotMessage: (token: string) => void;
  finishStreaming: () => void;
  setProcessing: (v: boolean) => void;
  setThinking: (v: boolean) => void;
  toggleStreaming: () => void;
  clearMessages: () => void;
  setUndoRedo: (undo: boolean, redo: boolean) => void;
  setQuickReplies: (replies: string[]) => void;
  replaceMessages: (messages: ChatMessage[]) => void;
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
  selectedBotId: null,
  selectedBotName: null,
  isProcessing: false,
  isThinking: false,
  streamingEnabled: loadStreamingPref(),
  undoAvailable: false,
  redoAvailable: false,
  quickReplies: [],

  setSelectedBot: (botId, botName) =>
    set({
      selectedBotId: botId,
      selectedBotName: botName,
      conversationId: null,
      messages: [],
    }),

  setConversationId: (id) => set({ conversationId: id }),

  addMessage: (message) =>
    set((s) => ({ messages: [...s.messages, message] })),

  appendToLastBotMessage: (token) =>
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === "bot") {
        msgs[msgs.length - 1] = { ...last, content: last.content + token };
      }
      return { messages: msgs };
    }),

  finishStreaming: () =>
    set((s) => {
      const msgs = [...s.messages];
      const last = msgs[msgs.length - 1];
      if (last?.role === "bot") {
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

  clearMessages: () => set({ messages: [], conversationId: null, undoAvailable: false, redoAvailable: false, quickReplies: [] }),

  setUndoRedo: (undo, redo) => set({ undoAvailable: undo, redoAvailable: redo }),

  setQuickReplies: (replies) => set({ quickReplies: replies }),

  replaceMessages: (messages) => set({ messages }),

  reset: () =>
    set({
      messages: [],
      conversationId: null,
      selectedBotId: null,
      selectedBotName: null,
      isProcessing: false,
      isThinking: false,
      undoAvailable: false,
      redoAvailable: false,
      quickReplies: [],
    }),
}));

// --- TanStack Query Hooks ---

const CHAT_KEY = ["chat"] as const;

/** Fetch bot descriptors and filter to only those that are deployed. */
export function useDeployedBots() {
  return useQuery({
    queryKey: [...CHAT_KEY, "deployedBots"],
    queryFn: async () => {
      const descriptors = await getBotDescriptors(100, 0, "");
      // Deduplicate by name, keep latest version
      const grouped = new Map<
        string,
        BotDescriptor & { id: string; version: number }
      >();
      for (const bot of descriptors) {
        const { id, version } = parseResourceUri(bot.resource);
        const existing = grouped.get(bot.name);
        if (!existing || version > existing.version) {
          grouped.set(bot.name, { ...bot, id, version });
        }
      }
      const bots = Array.from(grouped.values());

      // Check deployment status for each
      const deployed: (BotDescriptor & { id: string; version: number })[] = [];
      for (const bot of bots) {
        try {
          const status = await getDeploymentStatus("unrestricted", bot.id, bot.version);
          if (status.status === "READY") {
            deployed.push(bot);
          }
        } catch {
          // skip bots whose status can't be fetched
        }
      }
      return deployed;
    },
    staleTime: 60_000,
  });
}

/** Start a new conversation with a bot, then GET to pick up welcome messages. */
export function useStartConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({ botId }: { botId: string }) => {
      const conversationId = await startConversation("unrestricted", botId);
      store.getState().setConversationId(conversationId);

      // GET immediately to pick up any welcome message
      const snapshot = await readConversation(
        "unrestricted",
        botId,
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
            role: "bot",
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

/** Send a message — auto-branches between streaming and non-streaming. */
export function useSendMessage() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({ message }: { message: string }) => {
      const state = store.getState();
      const { selectedBotId, conversationId, streamingEnabled } = state;
      if (!selectedBotId || !conversationId) {
        throw new Error("No active conversation");
      }

      // Add user message
      state.addMessage({
        id: `user-${Date.now()}`,
        role: "user",
        content: message,
        timestamp: Date.now(),
      });
      state.setProcessing(true);

      if (streamingEnabled) {
        // --- Streaming path ---
        state.addMessage({
          id: `bot-${Date.now()}`,
          role: "bot",
          content: "",
          timestamp: Date.now(),
          isStreaming: true,
        });

        const events = sendMessageStreaming(
          "unrestricted",
          selectedBotId,
          conversationId,
          { input: message }
        );

        for await (const event of events) {
          handleSSEEvent(event, store);
        }
        state.finishStreaming();
      } else {
        // --- Non-streaming path ---
        const snapshot = await sendMessage(
          "unrestricted",
          selectedBotId,
          conversationId,
          message
        );

        // Extract bot output from the last conversationOutput
        const lastOutput = snapshot.conversationOutputs?.[
          (snapshot.conversationOutputs?.length ?? 1) - 1
        ];
        const output = extractOutput(lastOutput);

        if (output) {
          state.addMessage({
            id: `bot-${Date.now()}`,
            role: "bot",
            content: output,
            timestamp: Date.now(),
          });
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
      store.getState().appendToLastBotMessage(event.data);
      break;
    case "done":
      store.getState().finishStreaming();
      break;
    case "error":
      store
        .getState()
        .appendToLastBotMessage(`\n\n⚠️ Error: ${event.data}`);
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
        id: `bot-${messages.length}-${Date.now()}`,
        role: "bot",
        content: output,
        timestamp: Date.now(),
      });
    }
  }
  return messages;
}

/** Fetch conversation history for the selected bot. */
export function useConversationHistory(botId: string | null) {
  return useQuery({
    queryKey: [...CHAT_KEY, "history", botId],
    queryFn: () => getConversationDescriptors(50, 0, "", botId ?? ""),
    enabled: !!botId,
    staleTime: 30_000,
  });
}

/** Load an existing conversation to resume it. */
export function useLoadConversation() {
  const store = useChatStore;
  return useMutation({
    mutationFn: async ({
      botId,
      conversationId,
    }: {
      botId: string;
      conversationId: string;
    }) => {
      store.getState().clearMessages();
      store.getState().setConversationId(conversationId);

      const snapshot = await readConversation(
        "unrestricted",
        botId,
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
      const { selectedBotId, conversationId } = store.getState();
      if (!selectedBotId || !conversationId) throw new Error("No active conversation");

      const snapshot = await undoConversationApi("unrestricted", selectedBotId, conversationId);
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
      const { selectedBotId, conversationId } = store.getState();
      if (!selectedBotId || !conversationId) throw new Error("No active conversation");

      const snapshot = await redoConversationApi("unrestricted", selectedBotId, conversationId);
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
