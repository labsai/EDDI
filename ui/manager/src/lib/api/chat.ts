import { api, ApiClientError } from "../api-client";
import { parseSseFrame } from "./sse-utils";
import type { SimpleConversationMemorySnapshot } from "./conversations";
import type { Environment } from "@/lib/constants";

// --- Types ---

export interface InputData {
  input: string;
  context?: Record<string, unknown>;
}

/** A file attached to a user message, shown as a chip/thumbnail on the bubble. */
export interface MessageAttachment {
  fileName: string;
  mimeType: string;
  sizeBytes?: number;
  /** Object URL for an inline image preview (revoked when the message is cleared). */
  previewUrl?: string;
  /** `false` when the file was too large to forward inline to the model. */
  forwardableInline?: boolean;
}

export interface ChatMessage {
  id: string;
  /**
   * "system" marks a transcript FACT rather than a turn: a recorded approval
   * decision or an outcome notice. Kept out of "agent" deliberately — it is not
   * something the model said, and every "is this an agent reply?" query (bubble
   * rendering, placeholder reconciliation, trace attachment) must keep ignoring
   * it.
   */
  role: "user" | "agent" | "system";
  content: string;
  timestamp: number;
  isStreaming?: boolean;
  /** Attachments the user sent alongside this message. */
  attachments?: MessageAttachment[];
  /**
   * Marks a transcript entry that is NOT an agent reply: a recorded human
   * decision on a gated pause, or a notice explaining an outcome that produced
   * no text.
   *
   * Exists so a decision ALWAYS leaves a trace. A resumed turn that answers with
   * nothing — because it paused again, or returned no output — used to add
   * nothing to the transcript, so approving was indistinguishable from nothing
   * happening.
   */
  kind?: "decision" | "notice";
  /**
   * Stable code for translation; `content` carries the English fallback.
   *
   * "partial" is a top-level APPROVED that rejected some of its calls — the
   * approval banner allows exactly that, and calling it "approved" would put a
   * claim in the permanent transcript the approver did not make.
   */
  code?: "approved" | "partial" | "rejected" | "rePaused" | "noReply" | "executed";
  /** "partial" only: how many calls were rejected inside the approved batch. */
  count?: number;
  /**
   * "executed" only: the comma-joined tool receipt ("setupAgent ✓, readAgent ✓")
   * interpolated into the localized template. Dynamic, so it cannot live in the
   * per-code translation the way the static notices do; `content` still carries
   * the full English fallback.
   */
  detail?: string;
}

export type SSEEventType =
  | "token"
  | "task_start"
  | "task_complete"
  | "task_failed"
  | "tool_call"
  | "cascade_step_start"
  | "cascade_escalation"
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

/**
 * Start a new conversation. Returns the conversation ID extracted from the
 * Location header.
 *
 * The environment is REQUIRED on the wire. The backend
 * (`IRestAgentEngine.startConversation`) reads `?environment=` and defaults it to
 * `production` — and this function used to accept the argument as `_environment`
 * and never send it, so every conversation the Manager started went to
 * production regardless of what the caller asked for. An agent deployed only to
 * `test` was therefore unreachable from the UI, and the failure read as a broken
 * agent rather than as the wrong environment.
 *
 * `userId` is optional and is passed straight through to the backend, which
 * stores it on the conversation descriptor. Its one use today is letting
 * machine-started conversations be told apart from an admin's own: the operator
 * activation probes tag theirs (see `OPERATOR_PROBE_USER_ID`) so that restoring
 * "your last conversation" cannot adopt a canary that is about to be ended
 * underneath it.
 */
export async function startConversation(
  environment: Environment,
  agentId: string,
  userId?: string
): Promise<string> {
  const params = new URLSearchParams({ environment });
  if (userId) params.set("userId", userId);
  const result = await api.post<{ location: string }>(
    `/agents/${agentId}/start?${params.toString()}`
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
    throw new ApiClientError(response.status, response.statusText, response.url);
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
    throw new ApiClientError(
      response.status,
      `Streaming failed: ${response.statusText}`,
      response.url,
    );
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

      // Frames are separated by a blank line. CRLF framing leaves a "\r" on the
      // preceding line, which parseSseFrame strips.
      const parts = buffer.split(/\r?\n\r?\n/);
      buffer = parts.pop() ?? "";

      for (const part of parts) {
        const frame = parseSseFrame(part, "token");
        if (!frame) continue;
        yield { type: frame.type as SSEEventType, data: frame.data };
      }
    }

    // No decoder flush here on purpose. `decode(value, {stream: true})` holds only
    // an INCOMPLETE trailing sequence, so a complete multi-byte character is never
    // at risk; the only thing a final `decode()` would add is a U+FFFD for a body
    // that was truncated mid-character, which then rides into the transcript as
    // content. Leave the malformed tail out rather than render a replacement glyph.
    //
    // A server that closes without a trailing blank line leaves the last frame
    // in the buffer; emit it rather than dropping the final chunk of a reply.
    const tail = parseSseFrame(buffer, "token");
    if (tail) yield { type: tail.type as SSEEventType, data: tail.data };
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
