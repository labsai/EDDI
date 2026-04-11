import { api } from "../api-client";
import type { SimpleConversationMemorySnapshot } from "./conversations";

// --- Types ---

export interface InputData {
  input: string;
  context?: Record<string, unknown>;
}

export interface ChatMessage {
  id: string;
  role: "user" | "agent";
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

/** Extract conversation ID from Location header (e.g. "/agents/CONV_ID?...") */
export function parseConversationIdFromLocation(location: string): string {
  const parts = location.split("/");
  // The conversationId may have query params — strip them
  const last = parts[parts.length - 1] || location;
  return last.split("?")[0] ?? last;
}

// --- API Functions ---

/** Start a new conversation. Returns the conversation ID extracted from Location header. */
export async function startConversation(
  _environment: string,
  agentId: string
): Promise<string> {
  const result = await api.post<{ location: string }>(
    `/agents/${agentId}/start`
  );
  return parseConversationIdFromLocation(result.location);
}

/** Read an existing conversation (GET). Used after start (welcome message) and to resume. */
export function readConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
  returnCurrentStepOnly = false
): Promise<SimpleConversationMemorySnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: String(returnCurrentStepOnly),
  });
  return api.get<SimpleConversationMemorySnapshot>(
    `/agents/${conversationId}?${params.toString()}`
  );
}

/** Send a plain-text message (non-streaming). */
export async function sendMessage(
  _environment: string,
  _agentId: string,
  conversationId: string,
  message: string
): Promise<SimpleConversationMemorySnapshot> {
  // Plain text requires raw fetch since api-client always sets JSON content-type
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  const response = await fetch(
    `${api.getBaseUrl()}/agents/${conversationId}?${params.toString()}`,
    {
      method: "POST",
      headers: {
        "Content-Type": "text/plain",
        ...api.getAuthHeader(),
      },
      body: message,
    }
  );
  if (!response.ok) {
    throw {
      status: response.status,
      message: response.statusText,
      url: response.url,
    };
  }
  return response.json();
}

/** Send a message with context (non-streaming). */
export function sendMessageWithContext(
  _environment: string,
  _agentId: string,
  conversationId: string,
  inputData: InputData
): Promise<SimpleConversationMemorySnapshot> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  return api.post<SimpleConversationMemorySnapshot>(
    `/agents/${conversationId}?${params.toString()}`,
    inputData
  );
}

/**
 * Send a message via SSE streaming.
 * Returns an async generator yielding SSE events.
 * Note: SSE streaming requires raw fetch for ReadableStream access,
 * but we still attach auth headers.
 */
export async function* sendMessageStreaming(
  _environment: string,
  _agentId: string,
  conversationId: string,
  inputData: InputData,
  signal?: AbortSignal,
): AsyncGenerator<SSEEvent> {
  const response = await fetch(
    `${api.getBaseUrl()}/agents/${conversationId}/stream`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...api.getAuthHeader(),
      },
      body: JSON.stringify(inputData),
      signal,
    }
  );

  if (!response.ok) {
    throw {
      status: response.status,
      message: `Streaming failed: ${response.statusText}`,
      url: response.url,
    };
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
export function endConversation(conversationId: string): Promise<void> {
  return api.post(`/agents/${conversationId}/endConversation`);
}

/** Undo the last conversation step. */
export function undoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string
): Promise<SimpleConversationMemorySnapshot> {
  return api.post<SimpleConversationMemorySnapshot>(
    `/agents/${conversationId}/undo`
  );
}

/** Redo a previously undone step. */
export function redoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string
): Promise<SimpleConversationMemorySnapshot> {
  return api.post<SimpleConversationMemorySnapshot>(
    `/agents/${conversationId}/redo`
  );
}

/** Rerun the last conversation step (retry after error). */
export function rerunLastStep(
  conversationId: string,
): Promise<void> {
  const params = new URLSearchParams({
    returnDetailed: "false",
    returnCurrentStepOnly: "true",
  });
  return api.post(`/agents/${conversationId}/rerun?${params.toString()}`);
}
