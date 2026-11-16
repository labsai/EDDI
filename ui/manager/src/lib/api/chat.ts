import {
  HttpClient,
  STREAM_FAILED_CONTEXT,
  createConversationApi,
  parseConversationIdFromLocation,
  type ContextMap,
  type SSEEvent,
  type SSEEventType,
} from "@eddi/core";
import { api, apiErrorFromResponse, isApiError } from "../api-client";
import type { SimpleConversationMemorySnapshot } from "./conversations";
import type { Environment } from "@/lib/constants";
import { seg } from "./path-segment";

/*
 * The Manager's binding of @eddi/core's agent conversation API.
 *
 * The protocol — the endpoints, the SSE reader, the empty-200 undo — is the
 * package's, shared with the Chat UI. What is kept here is the Manager's: the
 * signatures its hooks and their tests are written against, its message types,
 * and its errors. Every call goes through `ApiClient.authorizedFetch` (token
 * refresh, one 401 retry) and fails with an `ApiClientError` carrying the
 * backend's own sentence (`apiErrorFromResponse`), exactly as an `api.post` does.
 */
const conversationHttp = new HttpClient({
  baseUrl: api.getBaseUrl(),
  fetch: (url, init) => api.authorizedFetch(url, init),
  // The backend's own sentence when it sends one; the status phrase — "Streaming
  // failed: …" for a refused stream — only when the body has nothing to say.
  createError: (response, context) =>
    apiErrorFromResponse(
      response,
      context === STREAM_FAILED_CONTEXT ? `${STREAM_FAILED_CONTEXT}: ${response.statusText}` : response.statusText,
    ),
});

const conversation = createConversationApi(conversationHttp);

/**
 * The package encodes every id it puts in a path; the Manager also REFUSES the
 * ones no EDDI resource has (`seg` — a slash, `..`, whitespace), synchronously,
 * as every other Manager API wrapper does. Returns the id unencoded: the
 * package encodes it.
 */
function checkedId(conversationId: string): string {
  seg(conversationId);
  return conversationId;
}

export { parseConversationIdFromLocation };
export type { SSEEvent, SSEEventType };

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
  /**
   * An agent bubble that reports a failed turn (a send that errored, or a
   * stream that ended on an `error` frame). The chat offers "Retry Last Step"
   * on it. A flag rather than a match on the text, which is translated.
   */
  isError?: boolean;
  /** Guardrails that fired on the turn this agent bubble answers, in order. */
  guardrails?: GuardrailNotice[];
  /**
   * The conversation step this message belongs to, when the source said so (a
   * transcript rebuilt from a snapshot). Reply ratings are keyed by it; live
   * messages are counted from the nearest one that has it (`rating-steps.ts` in @eddi/core).
   */
  stepIndex?: number;
}

/**
 * A guardrail that fired during a turn (`guardrail_triggered`). Carries what the
 * backend sends and nothing of the screened text.
 */
export interface GuardrailNotice {
  phase: string;
  type: string;
  action: string;
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
 *
 * Still an `api.post`, not the package's `startConversation`: `ApiClient` takes
 * the id from the `Location` header or, failing that, from a `location` field
 * in the body, and the Manager's mock backend answers with the body. The
 * package reads the header only, which is what EDDI sends.
 */
export async function startConversation(
  environment: Environment,
  agentId: string,
  userId?: string
): Promise<string> {
  const params = new URLSearchParams({ environment });
  if (userId) params.set("userId", userId);
  const result = await api.post<{ location: string }>(
    `/agents/${seg(agentId)}/start?${params.toString()}`
  );
  return parseConversationIdFromLocation(result.location);
}

/** Read an existing conversation (GET). Used after start (welcome message) and to resume. */
export async function readConversation(
  _environment: string,
  _agentId: string,
  conversationId: string,
  returnCurrentStepOnly = false
): Promise<SimpleConversationMemorySnapshot> {
  // The Manager reads the snapshot through its own, wider type.
  const snapshot: unknown = await conversation.readConversation(checkedId(conversationId), returnCurrentStepOnly);
  return snapshot as SimpleConversationMemorySnapshot;
}

/** Send a plain-text message (non-streaming). */
export async function sendMessage(
  _environment: string,
  _agentId: string,
  conversationId: string,
  message: string
): Promise<SimpleConversationMemorySnapshot> {
  const snapshot: unknown = await conversation.sendMessage(checkedId(conversationId), message);
  return snapshot as SimpleConversationMemorySnapshot;
}

/** Send a message with context (non-streaming). */
export async function sendMessageWithContext(
  _environment: string,
  _agentId: string,
  conversationId: string,
  inputData: InputData
): Promise<SimpleConversationMemorySnapshot> {
  const snapshot: unknown = await conversation.sendMessage(checkedId(conversationId), inputData.input, {
    context: inputData.context as ContextMap | undefined,
  });
  return snapshot as SimpleConversationMemorySnapshot;
}

/**
 * Send a message via SSE streaming. Yields the events as they arrive; the
 * request is made on the first pull. Framing is `@eddi/core`'s `readSseStream`.
 */
export async function* sendMessageStreaming(
  _environment: string,
  _agentId: string,
  conversationId: string,
  inputData: InputData,
  signal?: AbortSignal,
): AsyncGenerator<SSEEvent> {
  yield* conversation.sendMessageStreaming(checkedId(conversationId), inputData.input, {
    context: inputData.context as ContextMap | undefined,
    signal,
  });
}

/** End a conversation. */
export function endConversation(conversationId: string): Promise<void> {
  return conversation.endConversation(checkedId(conversationId));
}

/**
 * Report whether the backend actually performed an undo/redo.
 *
 * `RestAgentEngine.undo`/`redo` answer an EMPTY 200 when the step moved and a
 * 409 when there was nothing to move (or a concurrent turn / a HITL pause got
 * there first). They never return a snapshot. This used to be typed as one, so
 * the hook read `.conversationSteps` off `undefined` and threw — after the
 * server had already undone the step — leaving the transcript unchanged while
 * every further click undid another turn. The caller re-reads the conversation
 * instead of trusting a body.
 */
async function stepMoved(move: Promise<unknown>): Promise<boolean> {
  try {
    await move;
    return true;
  } catch (err) {
    if (isApiError(err) && err.status === 409) return false;
    throw err;
  }
}

/**
 * Undo the last conversation step. Resolves `true` when the backend moved the
 * step, `false` on its 409 "nothing to undo". Re-read the conversation either
 * way — a 409 can mean someone else changed it.
 */
export function undoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string
): Promise<boolean> {
  return stepMoved(conversation.undoConversation(checkedId(conversationId)));
}

/** Redo a previously undone step. Same contract as {@link undoConversation}. */
export function redoConversation(
  _environment: string,
  _agentId: string,
  conversationId: string
): Promise<boolean> {
  return stepMoved(conversation.redoConversation(checkedId(conversationId)));
}

/** Rerun the last conversation step (retry after error). */
export async function rerunLastStep(
  conversationId: string,
): Promise<void> {
  await conversation.rerunLastStep(checkedId(conversationId), {
    returnDetailed: false,
    returnCurrentStepOnly: true,
  });
}
