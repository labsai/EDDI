import { describe, it, expect } from "vitest";
import { chatReducer, initialState, type ChatState, type ChatAction } from "./chat-store";
import type { ChatMessage } from "@/types";

const makeMsg = (overrides: Partial<ChatMessage> = {}): ChatMessage => ({
  id: `msg-${Date.now()}`,
  role: "agent",
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

  it("APPEND_TO_LAST_AGENT appends token to last agent message", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg({ role: "agent", content: "Hel" })],
    };
    const result = chatReducer(state, { type: "APPEND_TO_LAST_AGENT", token: "lo" });
    expect(result.messages[0].content).toBe("Hello");
  });

  it("APPEND_TO_LAST_AGENT does nothing if last message is user", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg({ role: "user", content: "Hi" })],
    };
    const result = chatReducer(state, { type: "APPEND_TO_LAST_AGENT", token: "!" });
    expect(result.messages[0].content).toBe("Hi");
  });

  it("FINISH_STREAMING marks last agent message as not streaming and resets processing/thinking", () => {
    const state: ChatState = {
      ...initialState,
      isProcessing: true,
      isThinking: true,
      messages: [makeMsg({ role: "agent", content: "Hello", isStreaming: true })],
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
      config: { title: "My Agent", theme: "light" },
    });
    expect(result.config.title).toBe("My Agent");
    expect(result.config.theme).toBe("light");
    // Original defaults preserved
    expect(result.config.enableMarkdown).toBe(true);
  });

  // --- Secret input tests ---

  it("SET_INPUT_FIELD sets activeInputField", () => {
    const field = { subType: "password", label: "Enter API key" };
    const result = chatReducer(initialState, { type: "SET_INPUT_FIELD", field });
    expect(result.activeInputField).toEqual(field);
  });

  it("CLEAR_INPUT_FIELD resets activeInputField to null", () => {
    const state: ChatState = {
      ...initialState,
      activeInputField: { subType: "password", label: "Key" },
    };
    const result = chatReducer(state, { type: "CLEAR_INPUT_FIELD" });
    expect(result.activeInputField).toBeNull();
  });

  it("TOGGLE_SECRET_MODE toggles isSecretMode", () => {
    expect(initialState.isSecretMode).toBe(false);
    const toggled = chatReducer(initialState, { type: "TOGGLE_SECRET_MODE" });
    expect(toggled.isSecretMode).toBe(true);
    const toggledBack = chatReducer(toggled, { type: "TOGGLE_SECRET_MODE" });
    expect(toggledBack.isSecretMode).toBe(false);
  });

  it("CLEAR_MESSAGES also resets activeInputField and isSecretMode", () => {
    const state: ChatState = {
      ...initialState,
      messages: [makeMsg()],
      activeInputField: { subType: "password", label: "Key" },
      isSecretMode: true,
    };
    const result = chatReducer(state, { type: "CLEAR_MESSAGES" });
    expect(result.activeInputField).toBeNull();
    expect(result.isSecretMode).toBe(false);
    expect(result.messages).toHaveLength(0);
  });

  it("initialState has secret fields properly defaulted", () => {
    expect(initialState.activeInputField).toBeNull();
    expect(initialState.isSecretMode).toBe(false);
  });
});

/* ─── Skipped-turn handling ─────────────────── */

describe("REMOVE_EMPTY_STREAMING_MESSAGE", () => {
  it("removes a trailing agent bubble that never received tokens", () => {
    // A skipped turn leaves an empty streaming bubble that renders as
    // "No response". It must be withdrawn, not left on screen.
    const state = {
      ...initialState,
      messages: [
        { id: "u1", role: "user" as const, content: "hi", timestamp: 1 },
        { id: "a1", role: "agent" as const, content: "", timestamp: 2, isStreaming: true },
      ],
    };

    const next = chatReducer(state, { type: "REMOVE_EMPTY_STREAMING_MESSAGE" });

    expect(next.messages).toHaveLength(1);
    expect(next.messages[0].id).toBe("u1");
  });

  it("keeps a trailing agent bubble that received content", () => {
    const state = {
      ...initialState,
      messages: [
        { id: "a1", role: "agent" as const, content: "hello", timestamp: 2, isStreaming: true },
      ],
    };

    const next = chatReducer(state, { type: "REMOVE_EMPTY_STREAMING_MESSAGE" });

    expect(next.messages).toHaveLength(1);
  });

  it("leaves a trailing user message untouched", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "hi", timestamp: 1 }],
    };

    const next = chatReducer(state, { type: "REMOVE_EMPTY_STREAMING_MESSAGE" });

    expect(next.messages).toHaveLength(1);
  });
});

/* ─── Pending attachments ───────────────────── */

describe("pending attachments", () => {
  const att = { storageRef: "r1", fileName: "a.pdf", mimeType: "application/pdf", sizeBytes: 3 };

  it("stages an uploaded attachment", () => {
    const next = chatReducer(initialState, { type: "ADD_ATTACHMENT", attachment: att });

    expect(next.pendingAttachments).toEqual([att]);
  });

  it("removes a staged attachment by storageRef", () => {
    const staged = { ...initialState, pendingAttachments: [att, { ...att, storageRef: "r2" }] };

    const next = chatReducer(staged, { type: "REMOVE_ATTACHMENT", storageRef: "r1" });

    expect(next.pendingAttachments.map((a) => a.storageRef)).toEqual(["r2"]);
  });

  it("clears staged attachments once the turn is sent", () => {
    const staged = { ...initialState, pendingAttachments: [att] };

    const next = chatReducer(staged, { type: "CLEAR_ATTACHMENTS" });

    expect(next.pendingAttachments).toEqual([]);
  });

  it("drops staged attachments when the conversation is reset", () => {
    const staged = { ...initialState, pendingAttachments: [att] };

    const next = chatReducer(staged, { type: "CLEAR_MESSAGES" });

    expect(next.pendingAttachments).toEqual([]);
  });

  it("starts with no staged attachments", () => {
    expect(initialState.pendingAttachments).toEqual([]);
  });
});
