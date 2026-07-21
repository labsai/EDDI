/* ──────────────────────────────────────────────
   EDDI Chat — Shared Types
   ────────────────────────────────────────────── */

/** A single chat message (user or agent). */
export interface ChatMessage {
  id: string;
  role: "user" | "agent";
  content: string;
  timestamp: number;
  /** True while the agent is still streaming tokens. */
  isStreaming?: boolean;
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
 * When `subType` is `"password"`, the chat UI renders a masked input.
 */
export interface InputField {
  subType: string;       // "password" | "text" | "email" etc.
  placeholder?: string;
  label?: string;
  defaultValue?: string;
}

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
 * Mirrors RestAgentEngineStreaming — all eight.
 *
 * Note: there is no "thinking" event. The UI previously declared one and the
 * backend never emitted it, so the thinking indicator was only ever cleared by
 * the first token — leaving it spinning forever on a turn that failed or
 * paused before producing one.
 */
export type SSEEventType =
  | "token"
  | "task_start"
  | "task_complete"
  | "task_failed"
  | "cascade_step_start"
  | "cascade_escalation"
  | "done"
  | "error";

export interface SSEEvent {
  type: SSEEventType;
  data: string;
}

/** Simplified conversation step from GET response. */
export interface ConversationStep {
  input?: string;
  output?: string;
  actions?: string[];
  quickReplies?: QuickReply[];
}

/** Conversation snapshot returned by the backend. */
export interface ConversationSnapshot {
  agentId: string;
  agentVersion: number;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: ConversationStep[];
  conversationOutputs?: ConversationOutput[];
  conversationProperties?: Record<string, unknown>;
  undoAvailable?: boolean;
  redoAvailable?: boolean;
}

/** A single output item from the backend output array. */
export interface OutputItem {
  type?: string;       // "text" | "inputField" | "image" etc.
  text?: string;
  subType?: string;    // for inputField: "password" | "text" etc.
  placeholder?: string;
  label?: string;
  defaultValue?: string;
}

/** Per-step output block from POST /agents responses. */
export interface ConversationOutput {
  actions?: string[];
  output?: OutputItem[];
  quickReplies?: QuickReply[];
}

/**
 * Configuration options for the chat widget.
 * All fields are optional and have sensible defaults.
 */
export interface ChatConfig {
  /** API base URL. Default: `window.location.origin` */
  apiBaseUrl?: string;
  /** Theme mode. Default: `"dark"` */
  theme?: "dark" | "light" | "system";
  /** Primary accent color (CSS value). Default: EDDI blue `#113B92` */
  accentColor?: string;
  /** Show the EDDI logo in the header. Default: `true` */
  showLogo?: boolean;
  /** Custom logo URL. Default: `/img/logo_eddi.png` */
  logoUrl?: string;
  /** Header title text. Default: `"EDDI"` */
  title?: string;
  /** Input field placeholder. Default: `"Type a message..."` */
  placeholder?: string;
  /** Enable SSE streaming. Default: `true` */
  enableStreaming?: boolean;
  /** Show quick-reply buttons. Default: `true` */
  enableQuickReplies?: boolean;
  /** Render markdown in agent messages. Default: `true` */
  enableMarkdown?: boolean;
  /** Enable KaTeX math rendering. Default: `true` */
  enableMath?: boolean;
  /** Enable code syntax highlighting. Default: `true` */
  enableCodeHighlight?: boolean;
  /** Show undo button. Default: `true` */
  enableUndo?: boolean;
  /** Show redo button. Default: `true` */
  enableRedo?: boolean;
  /** Show new conversation button. Default: `true` */
  enableNewConversation?: boolean;
  /** Show agent name in header (fetched from descriptor). Default: `true` */
  showAgentName?: boolean;
}
