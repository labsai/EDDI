/* ──────────────────────────────────────────────
   EDDI Chat — API Layer
   Pure fetch-based, zero external dependencies.
   v6: simplified paths — all conversation-scoped ops use only conversationId.
   ────────────────────────────────────────────── */

import type {
  ConversationSnapshot,
  SSEEvent,
  SSEEventType,
  ContextMap,
} from "@/types";
import {
  ApiError,
  encodeSegment,
  request,
  requestJson,
  setBaseUrl,
} from "./http";

export { setBaseUrl, ApiError };

/* ─── Conversation lifecycle ─────────────────── */

/**
 * Start a new conversation.
 * Returns the conversation ID extracted from the Location header.
 *
 * `environment` is the route's `/chat/{environment}/…` segment and is sent as
 * `?environment=`. The backend defaults a missing one to production, so
 * dropping it made "/chat/test/{id}" 404 for a test-only agent — and silently
 * talk to production for an agent deployed in both.
 */
export async function startConversation(
  environment: string,
  agentId: string,
  userId?: string,
): Promise<string> {
  const query = new URLSearchParams();
  if (environment) query.set("environment", environment);
  if (userId) query.set("userId", userId);
  const params = query.toString() ? `?${query}` : "";
  const res = await request(
    `/agents/${encodeSegment(agentId)}/start${params}`,
    { method: "POST" },
    "Failed to start conversation",
  );

  const location = res.headers.get("Location");
  if (!location) {
    throw new Error(
      "startConversation: server did not return a Location header",
    );
  }
  const segments = location.split("/");
  const last = segments[segments.length - 1] || location;
  return last.split("?")[0];
}

/**
 * Read an existing conversation (GET).
 * Used after start (to pick up welcome messages) and to resume.
 * @param _environment - Unused in v6 API (kept for caller compatibility)
 * @param _agentId - Unused in v6 API (kept for caller compatibility)
 */
export async function readConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
  currentStepOnly = false,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: String(currentStepOnly),
  });
  const snapshot = await requestJson<ConversationSnapshot>(
    `/agents/${encodeSegment(conversationId)}?${params}`,
    undefined,
    "Failed to read conversation",
  );
  if (!snapshot) throw new Error("readConversation: empty response body");
  return snapshot;
}

/**
 * Send a message (non-streaming) to a direct agent.
 * Returns the conversation snapshot with the agent's reply in `conversationOutputs`.
 * When `context` is provided, sends as JSON `InputData` instead of plain text.
 * @param _environment - Unused in v6 API (kept for caller compatibility)
 * @param _agentId - Unused in v6 API (kept for caller compatibility)
 */
export async function sendMessage(
  _environment: string,
  _agentId: string,
  conversationId: string,
  message: string,
  userId?: string,
  context?: ContextMap,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  if (userId) params.set("userId", userId);

  const hasContext = context && Object.keys(context).length > 0;

  const snapshot = await requestJson<ConversationSnapshot>(
    `/agents/${encodeSegment(conversationId)}?${params}`,
    {
      method: "POST",
      headers: {
        "Content-Type": hasContext ? "application/json" : "text/plain",
      },
      body: hasContext
        ? JSON.stringify({ input: message, context })
        : message,
    },
    "Failed to send message",
  );
  if (!snapshot) throw new Error("sendMessage: empty response body");
  return snapshot;
}

/**
 * Send a message via SSE streaming.
 * Yields parsed SSE events as they arrive.
 * @param _environment - Unused in v6 API (kept for caller compatibility)
 * @param _agentId - Unused in v6 API (kept for caller compatibility)
 */
export async function* sendMessageStreaming(
  _environment: string,
  _agentId: string,
  conversationId: string,
  message: string,
  context?: ContextMap,
  signal?: AbortSignal,
): AsyncGenerator<SSEEvent> {
  const body: Record<string, unknown> = { input: message };
  if (context && Object.keys(context).length > 0) {
    body.context = context;
  }

  const res = await request(
    `/agents/${encodeSegment(conversationId)}/stream`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal,
    },
    "Streaming failed",
  );

  const reader = res.body?.getReader();
  if (!reader) throw new Error("No readable stream");

  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Normalize CRLF → LF so the parser works with any line ending
      buffer = buffer.replace(/\r\n/g, "\n");

      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";

      for (const part of parts) {
        if (!part.trim()) continue;
        let eventType: SSEEventType | null = null;
        const dataLines: string[] = [];

        for (const line of part.split("\n")) {
          if (line.startsWith(":")) {
            // Comment / keep-alive — carries no event
            continue;
          }
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim() as SSEEventType;
          } else if (line.startsWith("data:")) {
            // Strip exactly ONE leading space — the SSE spec's delimiter — and
            // keep everything after it verbatim.
            //
            // EDDI writes that delimiter explicitly: RESTEasy's SseUtil emits
            // "data:" with no space of its own, so RestAgentEngineStreaming
            // .padDataLines prefixes every data line with one. A token " word"
            // therefore arrives as "data:  word". Keeping the delimiter put an
            // extra space in front of every token ("quota tion"); stripping
            // more than one would run words together again.
            dataLines.push(line[5] === " " ? line.slice(6) : line.slice(5));
          }
        }

        // A frame with neither an event name nor data (bare comment/keep-alive)
        // is not an event. Defaulting to "token" made every unrecognised frame
        // render as visible text in the agent's message.
        if (eventType === null && dataLines.length === 0) continue;

        yield {
          type: eventType ?? "token",
          data: dataLines.join("\n"),
        };
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/* ─── Managed agent endpoints ──────────────────── */

/**
 * Send a message to a managed agent (intent-based routing).
 * v6: path changed from /managedagents to /agents/managed
 */
function managedPath(intent: string, userId: string): string {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  return `/agents/managed/${encodeSegment(intent)}/${encodeSegment(userId)}?${params}`;
}

/** Load (or lazily create) a managed conversation without sending anything. */
export async function loadManagedConversation(
  intent: string,
  userId: string,
): Promise<ConversationSnapshot> {
  const snapshot = await requestJson<ConversationSnapshot>(
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
export async function sendManagedAgentMessage(
  intent: string,
  userId: string,
  message: string,
  context?: ContextMap,
): Promise<ConversationSnapshot> {
  const body: Record<string, unknown> = { input: message };
  if (context && Object.keys(context).length > 0) body.context = context;

  const snapshot = await requestJson<ConversationSnapshot>(
    managedPath(intent, userId),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
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
export async function endManagedConversation(
  intent: string,
  userId: string,
): Promise<void> {
  await request(
    `/agents/managed/${encodeSegment(intent)}/${encodeSegment(userId)}/endConversation`,
    { method: "POST" },
    "Failed to end conversation",
  );
}

/**
 * End a conversation.
 */
export async function endConversation(
  conversationId: string,
): Promise<void> {
  await request(
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
export async function rerunLastStep(
  conversationId: string,
  language?: string,
): Promise<ConversationSnapshot | null> {
  // `language` is optional, as on an ordinary message. Omitted, the rerun
  // carries no language context. It used to default to "en", which the
  // backend stores as the conversation's `lang` property: language-specific
  // outputs were filtered for that user from then on, and a managed
  // conversation compared every later load against it.
  const params = new URLSearchParams();
  if (language) params.set("language", language);
  const query = params.toString() ? `?${params}` : "";
  return requestJson<ConversationSnapshot>(
    `/agents/${encodeSegment(conversationId)}/rerun${query}`,
    { method: "POST" },
    "Failed to retry",
  );
}

/* ─── Undo / Redo ────────────────────────────── */

/**
 * Undo the last conversation step.
 */
export async function undoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
): Promise<ConversationSnapshot | null> {
  // The backend answers 200 with an EMPTY body; requestJson yields null there.
  // Callers re-read the snapshot rather than relying on a response payload.
  return requestJson<ConversationSnapshot>(
    `/agents/${encodeSegment(conversationId)}/undo`,
    { method: "POST" },
    "Failed to undo",
  );
}

/**
 * Redo a previously undone conversation step.
 */
export async function redoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
): Promise<ConversationSnapshot | null> {
  return requestJson<ConversationSnapshot>(
    `/agents/${encodeSegment(conversationId)}/redo`,
    { method: "POST" },
    "Failed to redo",
  );
}

/* ─── Agent descriptor ─────────────────────────── */

/**
 * Fetch the agent's display name from its descriptor.
 *
 * The name lives on the DESCRIPTOR (`/descriptorstore/descriptors/{id}/simple`),
 * which requires a version — `/agentstore/agents/{id}` returns the
 * configuration, which carries no name at all, and was called without the
 * bearer token and without a version, so it could never succeed.
 *
 * The descriptor store is an editor endpoint: an end user holding only
 * `eddi-user` is refused, and the header then shows the configured `title`.
 * Any failure resolves to `{}` — a name is decoration, never a reason to fail.
 */
export async function fetchAgentDescriptor(
  agentId: string,
  version: number,
): Promise<{ name?: string; description?: string }> {
  try {
    const params = new URLSearchParams({ version: String(version) });
    const data = await requestJson<{ name?: unknown; description?: unknown }>(
      `/descriptorstore/descriptors/${encodeSegment(agentId)}/simple?${params}`,
      undefined,
      "Failed to read agent descriptor",
    );
    return {
      name: typeof data?.name === "string" && data.name.trim() ? data.name : undefined,
      description: typeof data?.description === "string" ? data.description : undefined,
    };
  } catch {
    return {};
  }
}
