/* ──────────────────────────────────────────────
   How an AgentConversation reaches its agent

   Three kinds of route speak the same turn protocol:

   - `conversation` — a conversation with a deployed agent: start (or resume)
     by id, then stream or say, read, undo, redo, rerun, cancel.
   - `managed` — an intent and a user id; the backend picks the agent and the
     conversation, and answers every turn as a snapshot (no stream).
   - `local` — no server at all (the Chat UI's demo): a scripted welcome and
     scripted events.

   The controller holds the turn logic; a transport is only the calls. The two
   server-backed ones are built on @eddi/core's conversation API; a host may
   pass its own client (the Manager's, with its token refresh).
   ────────────────────────────────────────────── */

import {
  cancelConversation as coreCancel,
  endManagedConversation,
  loadManagedConversation,
  readConversation,
  redoConversation,
  rerunLastStep,
  sendManagedAgentMessage,
  sendMessage,
  sendMessageStreaming,
  startConversation,
  undoConversation,
  type ContextMap,
  type ConversationSnapshot,
  type QuickReply,
  type SSEEvent,
} from "@eddi/core";
import type { ConversationMessage } from "./state";

export interface TurnOptions {
  /** `secretInput` and `attachment_N` entries; absent when there are none. */
  context?: ContextMap;
  /** Stop and New conversation abort the turn through it, on every transport. */
  signal: AbortSignal;
}

export interface LocalWelcome {
  conversationId: string;
  message: ConversationMessage;
  quickReplies: QuickReply[];
}

export interface AgentTransport {
  readonly mode: "conversation" | "managed" | "local";
  /** `conversation`: create a conversation; resolves its id. */
  start?(): Promise<string>;
  /**
   * `managed`: the current conversation for the intent and user, as a full
   * snapshot. `fresh` ends the current one first — a managed load always
   * returns the CURRENT conversation, so a restart that skipped this re-showed
   * the same one.
   */
  openManaged?(fresh: boolean): Promise<ConversationSnapshot>;
  /** `local`: the scripted greeting. */
  welcome?(): Promise<LocalWelcome>;
  /** A streamed turn (`conversation` with streaming on, and `local`). */
  stream?(conversationId: string | null, text: string, options: TurnOptions): AsyncIterable<SSEEvent>;
  /** A turn answered by one snapshot (`conversation` without streaming, `managed`). */
  say?(conversationId: string | null, text: string, options: TurnOptions): Promise<Partial<ConversationSnapshot>>;
  read(conversationId: string, currentStepOnly?: boolean): Promise<ConversationSnapshot>;
  undo(conversationId: string): Promise<unknown>;
  redo(conversationId: string): Promise<unknown>;
  rerun(conversationId: string): Promise<unknown>;
  cancel(conversationId: string): Promise<unknown>;
  /** `local`: the quick replies a scripted turn ends with. */
  quickRepliesAfter?(text: string): QuickReply[];
}

/** The calls a server-backed transport makes; @eddi/core's by default. */
export interface ConversationCalls {
  startConversation: typeof startConversation;
  readConversation: typeof readConversation;
  sendMessage: typeof sendMessage;
  sendMessageStreaming: typeof sendMessageStreaming;
  undoConversation: typeof undoConversation;
  redoConversation: typeof redoConversation;
  rerunLastStep: typeof rerunLastStep;
  cancelConversation: typeof coreCancel;
  loadManagedConversation: typeof loadManagedConversation;
  sendManagedAgentMessage: typeof sendManagedAgentMessage;
  endManagedConversation: typeof endManagedConversation;
}

/**
 * Looked up at call time, not captured: a test that mocks a function of
 * @eddi/core after this module loaded still sees its mock.
 */
function coreCalls(): ConversationCalls {
  return {
    startConversation,
    readConversation,
    sendMessage,
    sendMessageStreaming,
    undoConversation,
    redoConversation,
    rerunLastStep,
    cancelConversation: coreCancel,
    loadManagedConversation,
    sendManagedAgentMessage,
    endManagedConversation,
  };
}

function common(calls: () => ConversationCalls) {
  return {
    read: (id: string, currentStepOnly?: boolean) => calls().readConversation(id, currentStepOnly),
    undo: (id: string) => calls().undoConversation(id),
    redo: (id: string) => calls().redoConversation(id),
    rerun: (id: string) => calls().rerunLastStep(id),
    cancel: (id: string) => calls().cancelConversation(id),
  };
}

/** A conversation with a deployed agent in an environment. */
export function createConversationTransport(
  target: { environment: string; agentId: string; userId?: string },
  calls: () => ConversationCalls = coreCalls,
): AgentTransport {
  return {
    mode: "conversation",
    ...common(calls),
    start: () => calls().startConversation(target.environment, target.agentId, target.userId),
    stream: (id, text, { context, signal }) => calls().sendMessageStreaming(id ?? "", text, { context, signal }),
    say: (id, text, { context, signal }) =>
      calls().sendMessage(id ?? "", text, { userId: target.userId, context, signal }),
  };
}

/** A managed conversation: an intent and a user, the agent chosen by the backend. */
export function createManagedTransport(
  target: { intent: string; userId: string },
  calls: () => ConversationCalls = coreCalls,
): AgentTransport {
  return {
    mode: "managed",
    ...common(calls),
    openManaged: async (fresh) => {
      if (fresh) await calls().endManagedConversation(target.intent, target.userId);
      return calls().loadManagedConversation(target.intent, target.userId);
    },
    say: (_id, text, { context, signal }) =>
      calls().sendManagedAgentMessage(target.intent, target.userId, text, context, signal),
  };
}
