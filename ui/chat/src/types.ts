/* ──────────────────────────────────────────────
   EDDI Chat — Shared Types
   ────────────────────────────────────────────── */

/** A single chat message (user or bot). */
export interface ChatMessage {
  id: string;
  role: "user" | "bot";
  content: string;
  timestamp: number;
  /** True while the bot is still streaming tokens. */
  isStreaming?: boolean;
}

/** A quick-reply button returned by the backend. */
export interface QuickReply {
  value: string;
  expressions?: string;
}

/** Backend conversation states. */
export type ConversationState =
  | "READY"
  | "IN_PROGRESS"
  | "ERROR"
  | "ENDED";

/** SSE event types used by the streaming endpoint. */
export type SSEEventType =
  | "token"
  | "task_start"
  | "task_complete"
  | "thinking"
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
  botId: string;
  botVersion: number;
  conversationId: string;
  conversationState: ConversationState;
  environment: string;
  conversationSteps: ConversationStep[];
  conversationOutputs?: ConversationOutput[];
  conversationProperties?: Record<string, unknown>;
  undoAvailable?: boolean;
  redoAvailable?: boolean;
}

/** Per-step output block from POST /bots responses. */
export interface ConversationOutput {
  actions?: string[];
  output?: { text: string }[];
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
  /** Render markdown in bot messages. Default: `true` */
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
  /** Show bot name in header (fetched from descriptor). Default: `true` */
  showBotName?: boolean;
}
