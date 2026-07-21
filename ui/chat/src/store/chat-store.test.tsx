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

/* ─── HITL approval status ──────────────────── */

describe("approval status", () => {
  const status = {
    conversationId: "c1",
    state: "AWAITING_HUMAN" as const,
    pausedAt: "2026-07-21T10:00:00Z",
    pauseReason: "needs approval",
    timeoutPolicy: "AUTO_REJECT",
    approvalTimeout: "PT15M",
    pauseDetails: null,
  };

  it("starts with no approval status", () => {
    expect(initialState.approvalStatus).toBeNull();
  });

  it("stores the approval status while paused", () => {
    const next = chatReducer(initialState, { type: "SET_APPROVAL_STATUS", status });

    expect(next.approvalStatus).toEqual(status);
  });

  it("clears the approval status once the pause resolves", () => {
    const pausedState = { ...initialState, approvalStatus: status };

    const next = chatReducer(pausedState, { type: "SET_APPROVAL_STATUS", status: null });

    expect(next.approvalStatus).toBeNull();
  });

  it("drops the approval status when the conversation is reset", () => {
    const pausedState = { ...initialState, approvalStatus: status };

    expect(chatReducer(pausedState, { type: "CLEAR_MESSAGES" }).approvalStatus).toBeNull();
  });
});

/* ─── Rejected turn: restore the user's input ─── */

describe("withdrawing a turn the server refused", () => {
  it("removes the optimistic user bubble and restores the text as a draft", () => {
    // A 409 means the message was NEVER consumed server-side. Leaving it in
    // the transcript implies it was sent; discarding it loses what was typed.
    const state = {
      ...initialState,
      messages: [
        { id: "u1", role: "user" as const, content: "hello there", timestamp: 1 },
      ],
    };

    const next = chatReducer(state, { type: "WITHDRAW_LAST_USER_MESSAGE" });

    expect(next.messages).toHaveLength(0);
    expect(next.restoreDraft).toBe("hello there");
  });

  it("also withdraws the empty agent placeholder created for the turn", () => {
    const state = {
      ...initialState,
      messages: [
        { id: "u1", role: "user" as const, content: "hi", timestamp: 1 },
        { id: "a1", role: "agent" as const, content: "", timestamp: 2, isStreaming: true },
      ],
    };

    const next = chatReducer(state, { type: "WITHDRAW_LAST_USER_MESSAGE" });

    expect(next.messages).toHaveLength(0);
    expect(next.restoreDraft).toBe("hi");
  });

  it("does nothing when there is no user message to withdraw", () => {
    const next = chatReducer(initialState, { type: "WITHDRAW_LAST_USER_MESSAGE" });

    expect(next.restoreDraft).toBeNull();
  });

  it("clears the draft once the composer has consumed it", () => {
    const state = { ...initialState, restoreDraft: "hello" };

    expect(chatReducer(state, { type: "CLEAR_RESTORE_DRAFT" }).restoreDraft).toBeNull();
  });
});

/* ─── Reconciling streamed text with the final snapshot ─── */

describe("RECONCILE_LAST_AGENT", () => {
  const streamed = (content: string) => ({
    ...initialState,
    messages: [
      { id: "a1", role: "agent" as const, content, timestamp: 1, isStreaming: false },
    ],
  });

  it("replaces streamed text the backend superseded", () => {
    // responseValidation `fallback` substitutes a canned string for the model's
    // output AFTER tokens were already streamed to the client. The done
    // snapshot is authoritative; leaving the streamed text shows the user a
    // response the backend decided not to give.
    const next = chatReducer(streamed("here is your account number 1234"), {
      type: "RECONCILE_LAST_AGENT",
      content: "I wasn't able to generate a complete response.",
    });

    expect(next.messages[0].content).toBe(
      "I wasn't able to generate a complete response.",
    );
  });

  it("leaves the bubble alone when the snapshot agrees with what was streamed", () => {
    const state = streamed("hello world");

    const next = chatReducer(state, {
      type: "RECONCILE_LAST_AGENT",
      content: "hello world",
    });

    expect(next.messages).toBe(state.messages);
  });

  it("ignores whitespace-only differences", () => {
    const state = streamed("hello world");

    const next = chatReducer(state, {
      type: "RECONCILE_LAST_AGENT",
      content: "  hello world\n",
    });

    expect(next.messages).toBe(state.messages);
  });

  it("does not blank a bubble when the snapshot carries no text", () => {
    const state = streamed("hello world");

    const next = chatReducer(state, { type: "RECONCILE_LAST_AGENT", content: "" });

    expect(next.messages[0].content).toBe("hello world");
  });

  it("does nothing when the last message is not an agent message", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "hi", timestamp: 1 }],
    };

    expect(chatReducer(state, { type: "RECONCILE_LAST_AGENT", content: "x" }).messages).toBe(
      state.messages,
    );
  });
});

/* ─── Streaming bubble is targeted by identity, not position ─── */

describe("streaming bubble targeting", () => {
  const withNotice = () => ({
    ...initialState,
    messages: [
      { id: "a1", role: "agent" as const, content: "part", timestamp: 1, isStreaming: true },
      { id: "n1", role: "agent" as const, content: "⚠️ upload too large", timestamp: 2 },
    ],
  });

  it("appends tokens to the streaming bubble, not to a notice that arrived after it", () => {
    // An attachment error dispatched mid-stream used to land at the end of the
    // list, so every subsequent token was appended to the ERROR message.
    const next = chatReducer(withNotice(), { type: "APPEND_TO_LAST_AGENT", token: "-more" });

    expect(next.messages[0].content).toBe("part-more");
    expect(next.messages[1].content).toBe("⚠️ upload too large");
  });

  it("finishes the streaming bubble, not the notice", () => {
    const next = chatReducer(withNotice(), { type: "FINISH_STREAMING" });

    expect(next.messages[0].isStreaming).toBe(false);
    expect(next.messages[1].isStreaming).toBeUndefined();
  });

  it("reconciles the streaming bubble, not the notice", () => {
    const next = chatReducer(withNotice(), {
      type: "RECONCILE_LAST_AGENT",
      content: "authoritative",
    });

    expect(next.messages[0].content).toBe("authoritative");
    expect(next.messages[1].content).toBe("⚠️ upload too large");
  });

  it("removes the empty streaming bubble even when a notice follows it", () => {
    const state = {
      ...initialState,
      messages: [
        { id: "a1", role: "agent" as const, content: "", timestamp: 1, isStreaming: true },
        { id: "n1", role: "agent" as const, content: "⚠️ note", timestamp: 2 },
      ],
    };

    const next = chatReducer(state, { type: "REMOVE_EMPTY_STREAMING_MESSAGE" });

    expect(next.messages.map((m) => m.id)).toEqual(["n1"]);
  });
});

/* ─── Withdrawing a turn restores the REAL input ─── */

describe("withdrawing a turn restores the true composer input", () => {
  const att = { storageRef: "r1", fileName: "a.pdf", mimeType: "application/pdf", sizeBytes: 3 };

  it("restores the raw text, not the rendered bubble content", () => {
    // The bubble content may be a mask ("●●●●●●●●") or carry "📎 file" lines.
    // Restoring it would destroy the secret / corrupt the retry text.
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "📎 a.pdf\n\n●●●●●●●●", timestamp: 1 }],
    };

    const next = chatReducer(state, {
      type: "WITHDRAW_LAST_USER_MESSAGE",
      draft: "hunter2",
      attachments: [att],
    });

    expect(next.restoreDraft).toBe("hunter2");
  });

  it("puts the staged attachments back so they are not silently lost", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "x", timestamp: 1 }],
      pendingAttachments: [],
    };

    const next = chatReducer(state, {
      type: "WITHDRAW_LAST_USER_MESSAGE",
      draft: "x",
      attachments: [att],
    });

    expect(next.pendingAttachments).toEqual([att]);
  });

  it("falls back to the bubble content when no explicit draft is supplied", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "plain text", timestamp: 1 }],
    };

    const next = chatReducer(state, { type: "WITHDRAW_LAST_USER_MESSAGE" });

    expect(next.restoreDraft).toBe("plain text");
  });
});
