import type { SimpleConversationMemorySnapshot } from "./conversations";

// --- Types ---

export interface InputData {
  input: string;
  context?: Record<string, unknown>;
}

export interface ChatMessage {
  id: string;
  role: "user" | "bot";
  content: string;
  timestamp: number;
  isStreaming?: boolean;
}

export type SSEEventType =
  | "token"
  | "task_start"
  | "task_complete"
  | "done"
  | "error";

export interface SSEEvent {
  type: SSEEventType;
  data: string;
}

// --- Helpers ---

const BASE_URL = window.location.origin;

function buildUrl(path: string): string {
  return `${BASE_URL}${path}`;
}

/** Extract conversation ID from Location header (e.g. "/bots/unrestricted/bot1/CONV_ID") */
export function parseConversationIdFromLocation(location: string): string {
  const parts = location.split("/");
  return parts[parts.length - 1] || location;
}

// --- API Functions ---

/** Start a new conversation. Returns the location header containing the conversation ID. */
export async function startConversation(
  environment: string,
  botId: string
): Promise<string> {
  const response = await fetch(buildUrl(`/bots/${environment}/${botId}`), {
    method: "POST",
  });
  if (!response.ok) {
    throw new Error(`Failed to start conversation: ${response.statusText}`);
  }
  const location = response.headers.get("Location") ?? "";
  return parseConversationIdFromLocation(location);
}

/** Read an existing conversation (GET). Used after start (welcome message) and to resume. */
export async function readConversation(
  environment: string,
  botId: string,
  conversationId: string,
  returnCurrentStepOnly = false
): Promise<SimpleConversationMemorySnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: String(returnCurrentStepOnly),
  });
  const response = await fetch(
    buildUrl(
      `/bots/${environment}/${botId}/${conversationId}?${params.toString()}`
    )
  );
  if (!response.ok) {
    throw new Error(`Failed to read conversation: ${response.statusText}`);
  }
  return response.json();
}

/** Send a plain-text message (non-streaming). */
export async function sendMessage(
  environment: string,
  botId: string,
  conversationId: string,
  message: string
): Promise<SimpleConversationMemorySnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  const response = await fetch(
    buildUrl(
      `/bots/${environment}/${botId}/${conversationId}?${params.toString()}`
    ),
    {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: message,
    }
  );
  if (!response.ok) {
    throw new Error(`Failed to send message: ${response.statusText}`);
  }
  return response.json();
}

/** Send a message with context (non-streaming). */
export async function sendMessageWithContext(
  environment: string,
  botId: string,
  conversationId: string,
  inputData: InputData
): Promise<SimpleConversationMemorySnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  const response = await fetch(
    buildUrl(
      `/bots/${environment}/${botId}/${conversationId}?${params.toString()}`
    ),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(inputData),
    }
  );
  if (!response.ok) {
    throw new Error(`Failed to send message: ${response.statusText}`);
  }
  return response.json();
}

/**
 * Send a message via SSE streaming.
 * Returns an async generator yielding SSE events.
 */
export async function* sendMessageStreaming(
  environment: string,
  botId: string,
  conversationId: string,
  inputData: InputData
): AsyncGenerator<SSEEvent> {
  const response = await fetch(
    buildUrl(
      `/bots/${environment}/${botId}/${conversationId}/stream`
    ),
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(inputData),
    }
  );

  if (!response.ok) {
    throw new Error(`Streaming failed: ${response.statusText}`);
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error("No readable stream");

  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      // Parse SSE lines: "event: <type>\ndata: <data>\n\n"
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

/** End a conversation. */
export async function endConversation(
  conversationId: string
): Promise<void> {
  const response = await fetch(
    buildUrl(`/bots/${conversationId}/endConversation`),
    { method: "POST" }
  );
  if (!response.ok) {
    throw new Error(`Failed to end conversation: ${response.statusText}`);
  }
}
