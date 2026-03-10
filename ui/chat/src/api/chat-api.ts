/* ──────────────────────────────────────────────
   EDDI Chat — API Layer
   Pure fetch-based, zero external dependencies.
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

/* ─── Conversation lifecycle ─────────────────── */

/**
 * Start a new conversation.
 * Returns the conversation ID extracted from the Location header.
 */
export async function startConversation(
  environment: string,
  botId: string,
  userId?: string,
): Promise<string> {
  const params = userId ? `?userId=${encodeURIComponent(userId)}` : "";
  const res = await fetch(
    buildUrl(`/bots/${environment}/${botId}${params}`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to start conversation: ${res.statusText}`);

  const location = res.headers.get("Location") ?? "";
  const segments = location.split("/");
  return segments[segments.length - 1] || location;
}

/**
 * Read an existing conversation (GET).
 * Used after start (to pick up welcome messages) and to resume.
 */
export async function readConversation(
  environment: string,
  botId: string,
  conversationId: string,
  currentStepOnly = false,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: String(currentStepOnly),
  });
  const res = await fetch(
    buildUrl(`/bots/${environment}/${botId}/${conversationId}?${params}`),
  );
  if (!res.ok) throw new Error(`Failed to read conversation: ${res.statusText}`);
  return res.json();
}

/**
 * Send a message (non-streaming) to a direct bot.
 * Returns the conversation snapshot with the bot's reply in `conversationOutputs`.
 */
export async function sendMessage(
  environment: string,
  botId: string,
  conversationId: string,
  message: string,
  userId?: string,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  if (userId) params.set("userId", userId);

  const res = await fetch(
    buildUrl(`/bots/${environment}/${botId}/${conversationId}?${params}`),
    {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: message,
    },
  );
  if (!res.ok) throw new Error(`Failed to send message: ${res.statusText}`);
  return res.json();
}

/**
 * Send a message via SSE streaming.
 * Yields parsed SSE events as they arrive.
 */
export async function* sendMessageStreaming(
  environment: string,
  botId: string,
  conversationId: string,
  message: string,
): AsyncGenerator<SSEEvent> {
  const res = await fetch(
    buildUrl(`/bots/${environment}/${botId}/${conversationId}/stream`),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ input: message }),
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

      const parts = buffer.split("\n\n");
      buffer = parts.pop() ?? "";

      for (const part of parts) {
        if (!part.trim()) continue;
        let eventType: SSEEventType = "token";
        let eventData = "";

        for (const line of part.split("\n")) {
          if (line.startsWith("event:")) {
            eventType = line.slice(6).trim() as SSEEventType;
          } else if (line.startsWith("data:")) {
            eventData = line.slice(5).trim();
          }
        }

        if (eventData || eventType) {
          yield { type: eventType, data: eventData };
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/* ─── Managed bot endpoints ──────────────────── */

/**
 * Send a message to a managed bot (intent-based routing).
 * Used when the URL is `/chat/managedbots/:intent/:userId`.
 */
export async function sendManagedBotMessage(
  intent: string,
  userId: string,
  message?: string,
): Promise<ConversationSnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  const url = buildUrl(`/managedbots/${intent}/${userId}?${params}`);

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
    buildUrl(`/bots/${conversationId}/endConversation`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to end conversation: ${res.statusText}`);
}

/* ─── Undo / Redo ────────────────────────────── */

/**
 * Undo the last conversation step.
 */
export async function undoConversation(
  environment: string,
  botId: string,
  conversationId: string,
): Promise<ConversationSnapshot> {
  const res = await fetch(
    buildUrl(`/bots/${environment}/${botId}/undo/${conversationId}`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to undo: ${res.statusText}`);
  return res.json();
}

/**
 * Redo a previously undone conversation step.
 */
export async function redoConversation(
  environment: string,
  botId: string,
  conversationId: string,
): Promise<ConversationSnapshot> {
  const res = await fetch(
    buildUrl(`/bots/${environment}/${botId}/redo/${conversationId}`),
    { method: "POST" },
  );
  if (!res.ok) throw new Error(`Failed to redo: ${res.statusText}`);
  return res.json();
}

/* ─── Bot descriptor ─────────────────────────── */

/**
 * Fetch the bot document descriptor to get the bot's display name.
 * Uses the GET /botstore/bots/:botId endpoint.
 */
export async function fetchBotDescriptor(
  botId: string,
): Promise<{ name?: string; description?: string }> {
  const res = await fetch(
    buildUrl(`/botstore/bots/${botId}`),
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
