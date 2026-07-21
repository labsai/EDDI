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
  buildUrl,
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
 */
export async function startConversation(
  _environment: string,
  agentId: string,
  userId?: string,
): Promise<string> {
  const params = userId ? `?userId=${encodeURIComponent(userId)}` : "";
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
            // Per the SSE spec exactly ONE optional space after the colon is
            // the delimiter. Everything after it is payload — trimming here
            // destroys significant indentation in code blocks and markdown.
            const raw = line.slice(5);
            dataLines.push(raw.startsWith(" ") ? raw.slice(1) : raw);
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
export async function sendManagedAgentMessage(
  intent: string,
  userId: string,
  message?: string,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  const path = `/agents/managed/${encodeSegment(intent)}/${encodeSegment(userId)}?${params}`;

  const snapshot = message
    ? await requestJson<ConversationSnapshot>(
        path,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ input: message }),
        },
        "Failed to send message",
      )
    : await requestJson<ConversationSnapshot>(path, { method: "GET" }, "Failed to load conversation");

  if (!snapshot) throw new Error("sendManagedAgentMessage: empty response body");
  return snapshot;
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
  language = "en",
): Promise<ConversationSnapshot | null> {
  // `language` is REQUIRED: the backend declares it without @DefaultValue and
  // calls checkNotEmpty(language) before any other work, so omitting it is an
  // unconditional 400 — the retry could never have worked.
  const params = new URLSearchParams({ language });
  return requestJson<ConversationSnapshot>(
    `/agents/${encodeSegment(conversationId)}/rerun?${params}`,
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
 * Fetch the agent document descriptor to get the agent's display name.
 * Uses the GET /agentstore/agents/:agentId endpoint.
 */
export async function fetchAgentDescriptor(
  agentId: string,
): Promise<{ name?: string; description?: string }> {
  const res = await fetch(
    buildUrl(`/agentstore/agents/${encodeSegment(agentId)}`),
  );
  if (!res.ok) return {};
  try {
    const data = await res.json();
    return {
      name: data?.resource?.name ?? data?.name,
      description: data?.resource?.description ?? data?.description,
    };
  } catch {
    return {};
  }
}
