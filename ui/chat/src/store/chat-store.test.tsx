import { describe, it, expect } from "vitest";
import { chatReducer, initialState, type ChatState, type ChatAction } from "./chat-store";
import type { ChatMessage } from "@/types";

const makeMsg = (overrides: Partial<ChatMessage> = {}): ChatMessage => ({
  id: `msg-${Date.now()}`,
  role: "bot",
  content: "Hello",
  timestamp: Date.now(),
  ...overrides,
});

describe("chatReducer", () => {
  it("returns initial state for unknown action", () => {
    const result = chatReducer(initialState, { type: "UNKNOWN" } as unknown as ChatAction);
    expect(result).toEqual(initialState);
  });

  it("SET_CONVERSATION_ID sets the conversation id", () => {
    const result = chatReducer(initialState, { type: "SET_CONVERSATION_ID", id: "conv-1" });
    expect(result.conversationId).toBe("conv-1");
  });

  it("SET_CONVERSATION_STATE sets the state", () => {
    const result = chatReducer(initialState, { type: "SET_CONVERSATION_STATE", state: "READY" });
    expect(result.conversationState).toBe("READY");
  });

  it("ADD_MESSAGE appends a message", () => {
    const msg = makeMsg({ content: "Hi" });
    const result = chatReducer(initialState, { type: "ADD_MESSAGE", message: msg });
    expect(result.messages).toHaveLength(1);
    expect(result.messages[0].content).toBe("Hi");
  });

  it("APPEND_TO_LAST_BOT appends token to last bot message", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg({ role: "bot", content: "Hel" })],
    };
    const result = chatReducer(state, { type: "APPEND_TO_LAST_BOT", token: "lo" });
    expect(result.messages[0].content).toBe("Hello");
  });

  it("APPEND_TO_LAST_BOT does nothing if last message is user", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg({ role: "user", content: "Hi" })],
    };
    const result = chatReducer(state, { type: "APPEND_TO_LAST_BOT", token: "!" });
    expect(result.messages[0].content).toBe("Hi");
  });

  it("FINISH_STREAMING marks last bot message as not streaming and resets processing/thinking", () => {
    const state: ChatState = {
      ...initialState,
      isProcessing: true,
      isThinking: true,
      messages: [makeMsg({ role: "bot", content: "Hello", isStreaming: true })],
    };
    const result = chatReducer(state, { type: "FINISH_STREAMING" });
    expect(result.messages[0].isStreaming).toBe(false);
    expect(result.isProcessing).toBe(false);
    expect(result.isThinking).toBe(false);
  });

  it("SET_QUICK_REPLIES sets quick replies", () => {
    const replies = [{ value: "Yes" }, { value: "No" }];
    const result = chatReducer(initialState, { type: "SET_QUICK_REPLIES", replies });
    expect(result.quickReplies).toEqual(replies);
  });

  it("SET_PROCESSING sets processing flag", () => {
    const result = chatReducer(initialState, { type: "SET_PROCESSING", value: true });
    expect(result.isProcessing).toBe(true);
  });

  it("SET_THINKING sets thinking flag", () => {
    const result = chatReducer(initialState, { type: "SET_THINKING", value: true });
    expect(result.isThinking).toBe(true);
  });

  it("CLEAR_MESSAGES resets messages, conversationId, quickReplies, and undo/redo", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg()],
      conversationId: "conv-1",
      quickReplies: [{ value: "Hi" }],
      undoAvailable: true,
      redoAvailable: true,
    };
    const result = chatReducer(state, { type: "CLEAR_MESSAGES" });
    expect(result.messages).toHaveLength(0);
    expect(result.conversationId).toBeNull();
    expect(result.quickReplies).toHaveLength(0);
    expect(result.undoAvailable).toBe(false);
    expect(result.redoAvailable).toBe(false);
  });

  it("SET_UNDO_REDO sets undo and redo availability", () => {
    const result = chatReducer(initialState, {
      type: "SET_UNDO_REDO",
      undoAvailable: true,
      redoAvailable: false,
    });
    expect(result.undoAvailable).toBe(true);
    expect(result.redoAvailable).toBe(false);
  });

  it("REPLACE_MESSAGES replaces all messages", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg({ content: "old" })],
    };
    const newMsgs = [makeMsg({ content: "new1" }), makeMsg({ content: "new2" })];
    const result = chatReducer(state, { type: "REPLACE_MESSAGES", messages: newMsgs });
    expect(result.messages).toHaveLength(2);
    expect(result.messages[0].content).toBe("new1");
  });

  it("SET_CONFIG merges config", () => {
    const result = chatReducer(initialState, {
      type: "SET_CONFIG",
      config: { title: "My Bot", theme: "light" },
    });
    expect(result.config.title).toBe("My Bot");
    expect(result.config.theme).toBe("light");
    // Original defaults preserved
    expect(result.config.enableMarkdown).toBe(true);
  });
});
