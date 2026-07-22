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

/* ─── Model cascade escalation ──────────────── */

describe("cascade escalation", () => {
  it("SET_ESCALATING toggles the flag", () => {
    const on = chatReducer(initialState, { type: "SET_ESCALATING", value: true });
    expect(on.isEscalating).toBe(true);

    expect(
      chatReducer(on, { type: "SET_ESCALATING", value: false }).isEscalating,
    ).toBe(false);
  });

  it("FINISH_STREAMING clears it alongside thinking and processing", () => {
    // Clearing here rather than at each call site is what covers every exit
    // path — done, error, stop-generating, and a stream that closed without a
    // done event all reduce through this action.
    const state: ChatState = {
      ...initialState,
      isEscalating: true,
      isThinking: true,
      isProcessing: true,
      messages: [makeMsg({ isStreaming: true })],
    };

    const result = chatReducer(state, { type: "FINISH_STREAMING" });

    expect(result.isEscalating).toBe(false);
    expect(result.isThinking).toBe(false);
    expect(result.isProcessing).toBe(false);
  });

  it("CLEAR_MESSAGES clears it, so a new conversation never inherits it", () => {
    const state: ChatState = { ...initialState, isEscalating: true };

    expect(chatReducer(state, { type: "CLEAR_MESSAGES" }).isEscalating).toBe(false);
  });

  it("SET_ESCALATING returns the SAME state object when unchanged", () => {
    // Dispatched on every token; a fresh object each time re-renders every
    // consumer of the store for nothing.
    const state: ChatState = { ...initialState, isEscalating: false };

    expect(chatReducer(state, { type: "SET_ESCALATING", value: false })).toBe(state);
    expect(chatReducer(state, { type: "SET_THINKING", value: false })).toBe(state);
  });
});

describe("CLEAR_MESSAGES resets the turn flags", () => {
  it("clears isProcessing — a cleared conversation is not mid-turn", () => {
    // This was only ever lowered as a side effect of the abandoned stream's
    // FINISH_STREAMING. Guarding that continuation stranded the flag and the
    // NEW conversation opened with a disabled composer and a live Stop button.
    const state: ChatState = {
      ...initialState,
      isProcessing: true,
      isThinking: true,
      isEscalating: true,
    };

    const result = chatReducer(state, { type: "CLEAR_MESSAGES" });

    expect(result.isProcessing).toBe(false);
    expect(result.isThinking).toBe(false);
    expect(result.isEscalating).toBe(false);
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

/* ─── Snapshot messages are idempotent ─── */

describe("ADD_SNAPSHOT_MESSAGE", () => {
  const msg = (sourceKey: string, content = "hello") => ({
    id: `a-${sourceKey}`,
    role: "agent" as const,
    content,
    timestamp: 1,
    sourceKey,
  });

  it("adds a snapshot-derived message the first time", () => {
    const next = chatReducer(initialState, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: msg("out:0:0"),
    });

    expect(next.messages).toHaveLength(1);
  });

  it("does not re-add the same snapshot text on a refresh", () => {
    // handleRetry and the post-approval refresh both re-read the SAME step.
    // Appending blindly duplicated the transcript every time.
    const once = chatReducer(initialState, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: msg("out:0:0"),
    });

    const twice = chatReducer(once, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: msg("out:0:0"),
    });

    expect(twice.messages).toHaveLength(1);
    expect(twice).toBe(once);
  });

  it("still adds a genuinely different step", () => {
    const once = chatReducer(initialState, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: msg("out:0:0"),
    });

    const twice = chatReducer(once, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: msg("out:1:0", "second"),
    });

    expect(twice.messages).toHaveLength(2);
  });
});

/* ─── Withdrawing targets an exact turn ─── */

describe("WITHDRAW_LAST_USER_MESSAGE targets a specific turn", () => {
  it("withdraws the identified message, not merely the most recent user message", () => {
    // A late-failing turn used to withdraw whichever user message happened to
    // be last, silently deleting a newer, unrelated one.
    const state = {
      ...initialState,
      messages: [
        { id: "u1", role: "user" as const, content: "first", timestamp: 1 },
        { id: "u2", role: "user" as const, content: "second", timestamp: 2 },
      ],
    };

    const next = chatReducer(state, {
      type: "WITHDRAW_LAST_USER_MESSAGE",
      messageId: "u1",
      draft: "first",
    });

    expect(next.messages.map((m) => m.id)).toEqual(["u2"]);
    expect(next.restoreDraft).toBe("first");
  });

  it("is a no-op when the identified message is already gone", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u2", role: "user" as const, content: "second", timestamp: 2 }],
    };

    const next = chatReducer(state, {
      type: "WITHDRAW_LAST_USER_MESSAGE",
      messageId: "u1",
      draft: "first",
    });

    expect(next.messages.map((m) => m.id)).toEqual(["u2"]);
    expect(next.restoreDraft).toBeNull();
  });
});

describe("withdrawing a SECRET turn", () => {
  const secretState = {
    ...initialState,
    messages: [{ id: "u1", role: "user" as const, content: "●●●●●●●●", timestamp: 1 }],
  };

  it("does not hand the secret back as a restorable draft", () => {
    // The composer that would receive it is unmasked, and the secret marking
    // is lost — so the value would be shown in clear and re-sent unmarked.
    const next = chatReducer(secretState, {
      type: "WITHDRAW_LAST_USER_MESSAGE",
      messageId: "u1",
      draft: "hunter2",
      wasSecret: true,
    });

    expect(next.restoreDraft).toBeNull();
    expect(next.messages).toHaveLength(0);
  });

  it("still restores a non-secret draft", () => {
    const next = chatReducer(
      { ...initialState, messages: [{ id: "u1", role: "user" as const, content: "hi", timestamp: 1 }] },
      { type: "WITHDRAW_LAST_USER_MESSAGE", messageId: "u1", draft: "hi", wasSecret: false },
    );

    expect(next.restoreDraft).toBe("hi");
  });
});

describe("ADD_SNAPSHOT_MESSAGE dedupes against messages rendered by any path", () => {
  it("suppresses a text already shown, even though that message carries no sourceKey", () => {
    // The paused placeholder reaches the transcript via the streaming `done`
    // handler, which uses plain ADD_MESSAGE and stamps no key. Matching on
    // sourceKey alone could never see it, so the post-approval refresh
    // re-appended the placeholder it was written to suppress.
    const withPlaceholder = {
      ...initialState,
      messages: [
        {
          id: "a1",
          role: "agent" as const,
          content: "Waiting for approval to send the email.",
          timestamp: 1,
        },
      ],
    };

    const next = chatReducer(withPlaceholder, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: {
        id: "a2",
        role: "agent",
        content: "Waiting for approval to send the email.",
        timestamp: 2,
      },
    });

    expect(next.messages).toHaveLength(1);
    expect(next).toBe(withPlaceholder);
  });

  it("ignores surrounding whitespace when matching", () => {
    const state = {
      ...initialState,
      messages: [{ id: "a1", role: "agent" as const, content: "Done.", timestamp: 1 }],
    };

    const next = chatReducer(state, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: { id: "a2", role: "agent", content: "  Done.\n", timestamp: 2 },
    });

    expect(next.messages).toHaveLength(1);
  });

  it("still adds a genuinely different reply", () => {
    const state = {
      ...initialState,
      messages: [{ id: "a1", role: "agent" as const, content: "First answer.", timestamp: 1 }],
    };

    const next = chatReducer(state, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: { id: "a2", role: "agent", content: "Second answer.", timestamp: 2 },
    });

    expect(next.messages).toHaveLength(2);
  });

  it("does not match a USER message with the same text", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "hello", timestamp: 1 }],
    };

    const next = chatReducer(state, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: { id: "a1", role: "agent", content: "hello", timestamp: 2 },
    });

    expect(next.messages).toHaveLength(2);
  });
});

describe("ADD_SNAPSHOT_MESSAGE suppresses the user's own echo", () => {
  it("does not re-add the user bubble a snapshot re-read carries", () => {
    // A snapshot revisit carries input:initial as well as the outputs, so the
    // user's message came back and was appended a second time.
    const state = {
      ...initialState,
      messages: [
        { id: "u1", role: "user" as const, content: "book a flight", timestamp: 1 },
        { id: "a1", role: "agent" as const, content: "Here are 3 flights.", timestamp: 2 },
      ],
    };

    const next = chatReducer(state, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: { id: "u2", role: "user", content: "book a flight", timestamp: 3 },
    });

    expect(next.messages).toHaveLength(2);
  });

  it("still suppresses the agent reply on the same re-read", () => {
    const state = {
      ...initialState,
      messages: [
        { id: "u1", role: "user" as const, content: "book a flight", timestamp: 1 },
        { id: "a1", role: "agent" as const, content: "Here are 3 flights.", timestamp: 2 },
      ],
    };

    const next = chatReducer(state, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: { id: "a2", role: "agent", content: "Here are 3 flights.", timestamp: 3 },
    });

    expect(next.messages).toHaveLength(2);
  });

  it("does not let a user text suppress an agent message with the same words", () => {
    const state = {
      ...initialState,
      messages: [{ id: "u1", role: "user" as const, content: "hello", timestamp: 1 }],
    };

    const next = chatReducer(state, {
      type: "ADD_SNAPSHOT_MESSAGE",
      message: { id: "a1", role: "agent", content: "hello", timestamp: 2 },
    });

    expect(next.messages).toHaveLength(2);
  });
});
