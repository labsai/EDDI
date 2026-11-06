/* ──────────────────────────────────────────────
   @eddi/core — contract types
   The wire shapes of EDDI's 1:1 conversation API, verified against the backend
   source. The README lists the rules that go with them.
   ────────────────────────────────────────────── */

/**
 * Backend conversation states — mirrors EDDI's ConversationState enum
 * (ai.labs.eddi.engine.memory.model.ConversationState). All six values.
 */
export type ConversationState =
  | "READY"
  | "IN_PROGRESS"
  | "ERROR"
  | "ENDED"
  | "EXECUTION_INTERRUPTED"
  | "AWAITING_HUMAN";

/**
 * SSE event types emitted by POST /agents/{conversationId}/stream.
 * Mirrors RestAgentEngineStreaming — all twelve; stream-events.review.test.ts
 * reads the Java and fails when the two disagree. `tool_result` (B6) is
 * parsed by `@eddi/core/tool-events`; `llm_retry` and `cascade_escalation` also feed
 * the signed-in apps' turn notes (`@eddi/conversation/tool-activity`, T2.7).
 *
 * Note: there is no "thinking" event. A UI once declared one and the backend
 * never emitted it, so the thinking indicator was only ever cleared by the
 * first token — leaving it spinning forever on a turn that failed or paused
 * before producing one.
 */
export type SSEEventType =
  | "token"
  | "task_start"
  | "task_complete"
  | "task_failed"
  | "tool_call"
  | "tool_result"
  | "cascade_step_start"
  | "cascade_escalation"
  | "guardrail_triggered"
  | "llm_retry"
  | "done"
  | "error";

export interface SSEEvent {
  type: SSEEventType;
  data: string;
}

/**
 * Context entries sent alongside a message (EDDI's `Map<String, Context>`).
 *
 * `value` is deliberately `unknown`, not `string`: the attachment contract
 * requires an object-valued context (`{storageRef, fileName}`) under an
 * `attachment_N` key, which a string-valued type cannot express.
 * `type` mirrors EDDI's Context.ContextType enum.
 */
export type ContextType = "string" | "expressions" | "object" | "array";

export interface ContextEntry {
  type: ContextType;
  value: unknown;
}

export type ContextMap = Record<string, ContextEntry>;

/** A quick-reply button returned by the backend. */
export interface QuickReply {
  value: string;
  expressions?: string;
}

/**
 * An input field requested by the backend (from InputFieldOutputItem).
 * When `subType` is `"password"`, a client renders a masked input.
 */
export interface InputField {
  /** "password" | "text" | "email" etc. */
  subType: string;
  placeholder?: string;
  label?: string;
  defaultValue?: string;
}

/**
 * One datum inside a conversation step, as the backend actually sends it.
 * Mirrors SimpleConversationMemorySnapshot.ConversationStepData.
 */
export interface ConversationStepData {
  key: string;
  value: unknown;
  timestamp?: string;
  originWorkflowId?: string;
}

/**
 * A conversation step from GET /agents/{conversationId}.
 *
 * Mirrors SimpleConversationMemorySnapshot.SimpleConversationStep, which has
 * exactly these two properties. It notably does NOT have `input` or `output` —
 * a client type once declared both, and code that read them silently got
 * `undefined`, which is how undo/redo came to replace a transcript with an
 * empty list.
 */
export interface ConversationStep {
  conversationStep?: ConversationStepData[];
  timestamp?: string;
}

/** A single output item from the backend output array. */
export interface OutputItem {
  /** "text" | "inputField" | "image" | "applicationLink" | "button" etc. */
  type?: string;
  text?: string;
  /** for inputField: "password" | "text" etc. */
  subType?: string;
  placeholder?: string;
  label?: string;
  defaultValue?: string;
  /** image */
  uri?: string;
  alt?: string;
  /** applicationLink */
  path?: string;
}

/** Per-step output block from POST /agents responses. */
export interface ConversationOutput {
  /**
   * The display copy of the user's message — `<secret input>` for a turn sent
   * with `secretInput`, where the step's `input:initial` stays raw.
   */
  input?: string;
  actions?: string[];
  output?: OutputItem[];
  quickReplies?: QuickReply[];
}

/** Conversation snapshot returned by the backend. */
export interface ConversationSnapshot {
  agentId: string;
  agentVersion: number;
  /**
   * The agent's display name. The backend sets it on a conversation read, for
   * a caller who may use the agent — including one holding only `eddi-user`,
   * who cannot read the descriptor store. Absent when it cannot be resolved,
   * and on every other snapshot (say, stream `done`, undo/redo).
   */
  agentName?: string;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: ConversationStep[];
  conversationOutputs?: ConversationOutput[];
  conversationProperties?: Record<string, unknown>;
  undoAvailable?: boolean;
  redoAvailable?: boolean;
  /**
   * Why the conversation ended, when the backend recorded a reason — today
   * only {@link END_REASON_AGENT_VERSION_RETIRED}. Absent otherwise.
   */
  endReason?: string;
}

/**
 * `endReason` of a conversation ended because the agent version it ran on was
 * undeployed with "end all active conversations" — the assistant was updated
 * in a way the conversation could not follow (backend
 * `IConversationService.END_REASON_AGENT_VERSION_RETIRED`).
 */
export const END_REASON_AGENT_VERSION_RETIRED = "agent-version-retired";
