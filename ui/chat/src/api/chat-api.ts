/* ──────────────────────────────────────────────
   EDDI Chat — API Layer
   Pure fetch-based, zero external dependencies.
   v6: simplified paths — all conversation-scoped ops use only conversationId.
   ────────────────────────────────────────────── */

import type {
  ConversationSnapshot,
  SSEEvent,
  SSEEventType,
} from "@/types";

let _baseUrl = "";

/** Set the API base URL (e.g. from ChatConfig). Call once at startup. */
export function setBaseUrl(url: string): void {
  _baseUrl = url.replace(/\/$/, "");
}

function buildUrl(path: string): string {
  return `${_baseUrl}${path}`;
}

/** Encode a single path segment so /, ?, # in data don't break the URL. */
function encodeSegment(value: string): string {
  return encodeURIComponent(value);
}

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
  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(agentId)}/start${params}`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to start conversation: ${res.statusText}`);

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
  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(conversationId)}?${params}`),
  );
  if (!res.ok) throw new Error(`Failed to read conversation: ${res.statusText}`);
  return res.json();
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
  context?: Record<string, { type: string; value: string }>,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  if (userId) params.set("userId", userId);

  const hasContext = context && Object.keys(context).length > 0;

  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(conversationId)}?${params}`),
    {
      method: "POST",
      headers: {
        "Content-Type": hasContext ? "application/json" : "text/plain",
      },
      body: hasContext
        ? JSON.stringify({ input: message, context })
        : message,
    },
  );
  if (!res.ok) throw new Error(`Failed to send message: ${res.statusText}`);
  return res.json();
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
  context?: Record<string, { type: string; value: string }>,
  signal?: AbortSignal,
): AsyncGenerator<SSEEvent> {
  const body: Record<string, unknown> = { input: message };
  if (context && Object.keys(context).length > 0) {
    body.context = context;
  }

  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(conversationId)}/stream`),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal,
    },
  );

  if (!res.ok) throw new Error(`Streaming failed: ${res.statusText}`);

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
        let eventType: SSEEventType = "token";
        const dataLines: string[] = [];

        for (const line of part.split("\n")) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim() as SSEEventType;
          } else if (line.startsWith("data:")) {
            // Per SSE spec, join multiple data: lines with newlines
            dataLines.push(line.slice(5).trim());
          }
        }

        const eventData = dataLines.join("\n");

        if (eventData || eventType) {
          yield { type: eventType, data: eventData };
        }
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
  const url = buildUrl(
    `/agents/managed/${encodeSegment(intent)}/${encodeSegment(userId)}?${params}`,
  );

  if (message) {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ input: message }),
    });
    if (!res.ok) throw new Error(`Failed to send message: ${res.statusText}`);
    return res.json();
  } else {
    const res = await fetch(url, { method: "GET" });
    if (!res.ok) throw new Error(`Failed to load conversation: ${res.statusText}`);
    return res.json();
  }
}

/**
 * End a conversation.
 */
export async function endConversation(
  conversationId: string,
): Promise<void> {
  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(conversationId)}/endConversation`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to end conversation: ${res.statusText}`);
}

/* ─── Undo / Redo ────────────────────────────── */

/**
 * Undo the last conversation step.
 */
export async function undoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
): Promise<ConversationSnapshot> {
  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(conversationId)}/undo`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to undo: ${res.statusText}`);
  return res.json();
}

/**
 * Redo a previously undone conversation step.
 */
export async function redoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
): Promise<ConversationSnapshot> {
  const res = await fetch(
    buildUrl(`/agents/${encodeSegment(conversationId)}/redo`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to redo: ${res.statusText}`);
  return res.json();
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

/* ─── Attachments ────────────────────────────── */

export interface AttachmentResult {
  storageRef: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
}

/**
 * Upload a file attachment to a conversation.
 * POST /conversations/{conversationId}/attachments (multipart/form-data)
 */
export async function uploadAttachment(
  conversationId: string,
  file: File,
): Promise<AttachmentResult> {
  const formData = new FormData();
  formData.append("file", file);

  const res = await fetch(
    buildUrl(`/conversations/${encodeSegment(conversationId)}/attachments`),
    { method: "POST", body: formData },
  );

  if (!res.ok) throw new Error(`Attachment upload failed: ${res.statusText}`);
  return res.json();
}
