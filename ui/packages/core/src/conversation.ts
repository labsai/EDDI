/* ──────────────────────────────────────────────
   @eddi/core — the 1:1 agent conversation API
   Start, read, say (plain or streaming), undo, redo, rerun, end, the managed
   (intent-based) variants, and the agent profile. v6 paths: every
   conversation-scoped operation needs only the conversationId.

   Derived from the Chat UI's `api/chat-api.ts` and the Manager's
   `lib/api/chat.ts`, which spoke to the same endpoints with two copies of this
   code.
   ────────────────────────────────────────────── */

import { defaultHttp, encodeSegment, type HttpClient } from "./http";
import { readSseStream } from "./sse";
import type { ContextMap, ConversationSnapshot, SSEEvent, SSEEventType } from "./types";

/** GET /agents/{agentId}/profile. `rating` is parsed by `parseRatingProfile`. */
export interface AgentProfile {
  name?: string | null;
  reviewNotice?: string | null;
  /** How the deployed version asks for ratings; null/absent when it does not. */
  rating?: unknown;
}

export interface SendOptions {
  /** Sent as `?userId=`. */
  userId?: string;
  /**
   * Context entries. When present (and non-empty) the turn is sent as JSON
   * `InputData` instead of plain text — attachments reach the model ONLY this
   * way, through `attachment_N` keys.
   */
  context?: ContextMap;
  signal?: AbortSignal;
}

export interface StreamOptions {
  context?: ContextMap;
  signal?: AbortSignal;
}

export interface RerunOptions {
  /**
   * Optional, as on an ordinary message. Omitted, the rerun carries no
   * language context. It used to default to "en", which the backend stores as
   * the conversation's `lang` property: language-specific outputs were
   * filtered for that user from then on, and a managed conversation compared
   * every later load against it.
   */
  language?: string;
  /** Sent as `?returnDetailed=` when given. */
  returnDetailed?: boolean;
  /** Sent as `?returnCurrentStepOnly=` when given. */
  returnCurrentStepOnly?: boolean;
}

/**
 * The conversation id at the end of a `Location` header
 * (e.g. "/agents/CONV_ID?…"), query stripped.
 */
export function parseConversationIdFromLocation(location: string): string {
  const parts = location.split("/");
  const last = parts[parts.length - 1] || location;
  return last.split("?")[0] ?? last;
}

function hasContext(context: ContextMap | undefined): context is ContextMap {
  return !!context && Object.keys(context).length > 0;
}

/** The context string of a refused stream request, for an `ErrorFactory`. */
export const STREAM_FAILED_CONTEXT = "Streaming failed";

/** The agent conversation API over one {@link HttpClient}. */
export function createConversationApi(http: HttpClient) {
  /**
   * Start a new conversation. Returns the conversation ID extracted from the
   * Location header.
   *
   * `environment` is sent as `?environment=`. The backend defaults a missing
   * one to production, so dropping it made a test-only agent unreachable — and
   * silently talked to production for an agent deployed in both.
   *
   * `userId` is optional and stored on the conversation descriptor.
   */
  async function startConversation(environment: string, agentId: string, userId?: string): Promise<string> {
    const query = new URLSearchParams();
    if (environment) query.set("environment", environment);
    if (userId) query.set("userId", userId);
    const params = query.toString() ? `?${query}` : "";
    const res = await http.request(
      `/agents/${encodeSegment(agentId)}/start${params}`,
      { method: "POST" },
      "Failed to start conversation",
    );

    const location = res.headers.get("Location");
    if (!location) {
      throw new Error("startConversation: server did not return a Location header");
    }
    return parseConversationIdFromLocation(location);
  }

  /** Read an existing conversation (GET) — after start (welcome message) and to resume. */
  async function readConversation(conversationId: string, currentStepOnly = false): Promise<ConversationSnapshot> {
    const params = new URLSearchParams({
      returnDetailed: "false",
      returnCurrentStepOnly: String(currentStepOnly),
    });
    const snapshot = await http.requestJson<ConversationSnapshot>(
      `/agents/${encodeSegment(conversationId)}?${params}`,
      undefined,
      "Failed to read conversation",
    );
    if (!snapshot) throw new Error("readConversation: empty response body");
    return snapshot;
  }

  /**
   * Send a message (non-streaming). Returns the conversation snapshot with the
   * agent's reply in `conversationOutputs` (current step only).
   */
  async function sendMessage(
    conversationId: string,
    message: string,
    options: SendOptions = {},
  ): Promise<ConversationSnapshot> {
    const params = new URLSearchParams({
      returnDetailed: "false",
      returnCurrentStepOnly: "true",
    });
    if (options.userId) params.set("userId", options.userId);
    const json = hasContext(options.context);

    const snapshot = await http.requestJson<ConversationSnapshot>(
      `/agents/${encodeSegment(conversationId)}?${params}`,
      {
        method: "POST",
        headers: { "Content-Type": json ? "application/json" : "text/plain" },
        body: json ? JSON.stringify({ input: message, context: options.context }) : message,
        signal: options.signal,
      },
      "Failed to send message",
    );
    if (!snapshot) throw new Error("sendMessage: empty response body");
    return snapshot;
  }

  /**
   * Send a message via SSE streaming. Yields the events as they arrive.
   * The request is made on the first pull.
   */
  async function* sendMessageStreaming(
    conversationId: string,
    message: string,
    options: StreamOptions = {},
  ): AsyncGenerator<SSEEvent> {
    const body: Record<string, unknown> = { input: message };
    if (hasContext(options.context)) body.context = options.context;

    const res = await http.request(
      `/agents/${encodeSegment(conversationId)}/stream`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: options.signal,
      },
      STREAM_FAILED_CONTEXT,
    );

    for await (const frame of readSseStream(res.body, { defaultEventType: "token" })) {
      yield { type: frame.type as SSEEventType, data: frame.data };
    }
  }

  /** End a conversation. */
  async function endConversation(conversationId: string): Promise<void> {
    await http.request(
      `/agents/${encodeSegment(conversationId)}/endConversation`,
      { method: "POST" },
      "Failed to end conversation",
    );
  }

  /**
   * Re-execute the last conversation step.
   *
   * The recovery path after a turn fails: without it an ERROR or
   * EXECUTION_INTERRUPTED conversation is a dead end whose only escape is
   * starting over and losing the history.
   */
  function rerunLastStep(conversationId: string, options: RerunOptions = {}): Promise<ConversationSnapshot | null> {
    const params = new URLSearchParams();
    if (options.language) params.set("language", options.language);
    if (options.returnDetailed !== undefined) params.set("returnDetailed", String(options.returnDetailed));
    if (options.returnCurrentStepOnly !== undefined) {
      params.set("returnCurrentStepOnly", String(options.returnCurrentStepOnly));
    }
    const query = params.toString() ? `?${params}` : "";
    return http.requestJson<ConversationSnapshot>(
      `/agents/${encodeSegment(conversationId)}/rerun${query}`,
      { method: "POST" },
      "Failed to retry",
    );
  }

  /**
   * Undo the last conversation step.
   *
   * `RestAgentEngine.undo` answers an EMPTY 200 when the step moved — this
   * resolves null then — and a 409 when there was nothing to move (or a
   * concurrent turn / a HITL pause got there first), which throws with status
   * 409. It never returns a snapshot: re-read the conversation either way.
   */
  function undoConversation(conversationId: string): Promise<ConversationSnapshot | null> {
    return http.requestJson<ConversationSnapshot>(
      `/agents/${encodeSegment(conversationId)}/undo`,
      { method: "POST" },
      "Failed to undo",
    );
  }

  /** Redo a previously undone step. Same contract as `undoConversation`. */
  function redoConversation(conversationId: string): Promise<ConversationSnapshot | null> {
    return http.requestJson<ConversationSnapshot>(
      `/agents/${encodeSegment(conversationId)}/redo`,
      { method: "POST" },
      "Failed to redo",
    );
  }

  /* ─── Managed agents (intent-based routing) ─── */

  function managedPath(intent: string, userId: string): string {
    const params = new URLSearchParams({
      returnDetailed: "false",
      returnCurrentStepOnly: "true",
    });
    return `/agents/managed/${encodeSegment(intent)}/${encodeSegment(userId)}?${params}`;
  }

  /** Load (or lazily create) a managed conversation without sending anything. */
  async function loadManagedConversation(intent: string, userId: string): Promise<ConversationSnapshot> {
    const snapshot = await http.requestJson<ConversationSnapshot>(
      managedPath(intent, userId),
      { method: "GET" },
      "Failed to load conversation",
    );
    if (!snapshot) throw new Error("loadManagedConversation: empty response body");
    return snapshot;
  }

  /**
   * Send a message to a managed agent.
   *
   * The verb is NOT chosen from the truthiness of `message`: an attachment-only
   * turn has empty text and must still POST. And `context` must be forwarded —
   * it is the only path by which an attachment reaches the model.
   */
  async function sendManagedAgentMessage(
    intent: string,
    userId: string,
    message: string,
    context?: ContextMap,
    signal?: AbortSignal,
  ): Promise<ConversationSnapshot> {
    const body: Record<string, unknown> = { input: message };
    if (hasContext(context)) body.context = context;

    const snapshot = await http.requestJson<ConversationSnapshot>(
      managedPath(intent, userId),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal,
      },
      "Failed to send message",
    );
    if (!snapshot) throw new Error("sendManagedAgentMessage: empty response body");
    return snapshot;
  }

  /**
   * End the managed conversation for this intent and user. The next load then
   * starts a fresh one — a load alone always returns the existing conversation,
   * so "New conversation" on the managed route kept the agent's whole context.
   */
  async function endManagedConversation(intent: string, userId: string): Promise<void> {
    await http.request(
      `/agents/managed/${encodeSegment(intent)}/${encodeSegment(userId)}/endConversation`,
      { method: "POST" },
      "Failed to end conversation",
    );
  }

  /* ─── Agent profile ─── */

  /**
   * What a chat window shows about an agent: its name, and whether the people
   * who maintain it may read this conversation.
   *
   * Readable by anybody who may chat with the agent — unlike the configuration
   * store, which someone the agent was shared with "for chatting" cannot read.
   * An older EDDI without the endpoint answers 404. The name itself arrives
   * with the conversation read (`agentName` on the snapshot), which needs no
   * editor role.
   */
  function fetchAgentProfile(agentId: string, environment = "production"): Promise<AgentProfile | null> {
    return http.requestJson<AgentProfile>(
      `/agents/${encodeSegment(agentId)}/profile?environment=${encodeURIComponent(environment)}`,
      undefined,
      "Failed to read the agent profile",
    );
  }

  return {
    startConversation,
    readConversation,
    sendMessage,
    sendMessageStreaming,
    endConversation,
    rerunLastStep,
    undoConversation,
    redoConversation,
    loadManagedConversation,
    sendManagedAgentMessage,
    endManagedConversation,
    fetchAgentProfile,
  };
}

export type ConversationApi = ReturnType<typeof createConversationApi>;

/** The conversation API over {@link defaultHttp}. */
export const {
  startConversation,
  readConversation,
  sendMessage,
  sendMessageStreaming,
  endConversation,
  rerunLastStep,
  undoConversation,
  redoConversation,
  loadManagedConversation,
  sendManagedAgentMessage,
  endManagedConversation,
  fetchAgentProfile,
} = createConversationApi(defaultHttp);
