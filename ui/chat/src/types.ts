/* ──────────────────────────────────────────────
   EDDI Chat — Shared Types
   ────────────────────────────────────────────── */

/**
 * A single chat message (user, agent or a notice the widget wrote) — the shared
 * conversation message of @eddi/conversation.
 */
export type { ConversationMessage as ChatMessage } from "@eddi/conversation";

/*
 * The backend contract types live in @eddi/core and are re-exported here, so
 * the widget's own modules keep one place to import types from.
 */
export type {
  ContextType,
  ContextEntry,
  ContextMap,
  QuickReply,
  InputField,
  ConversationState,
  SSEEventType,
  SSEEvent,
  ConversationStepData,
  ConversationStep,
  ConversationSnapshot,
  OutputItem,
  ConversationOutput,
} from "@eddi/core";
export { END_REASON_AGENT_VERSION_RETIRED } from "@eddi/core";

/**
 * Configuration options for the chat widget.
 * All fields are optional and have sensible defaults.
 */
export interface ChatConfig {
  /** API base URL. Default: `window.location.origin` */
  apiBaseUrl?: string;
  /** Theme mode. Default: `"dark"` */
  theme?: "dark" | "light" | "system";
  /**
   * Primary accent color (CSS value; must pass `isSafeCssColor`). Applied the
   * same way as `?accentColor=`, which beats it. Default: the deployment brand's
   * chat accent when branded, else the stylesheet's EDDI blue `#113B92`.
   */
  accentColor?: string;
  /** Show the logo in the header. Default: `true` */
  showLogo?: boolean;
  /**
   * Custom logo URL; `""` shows the title as text instead. Default: the brand's
   * uploaded logo; the product name as text when branded without one; else
   * `/img/logo_eddi.png`.
   */
  logoUrl?: string;
  /** Header title text. Default: the brand's product name, else `"EDDI"` */
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
  /**
   * Bearer token for the EDDI API. Required when the deployment enables OIDC:
   * conversation ownership is enforced server-side, so an anonymous client can
   * be locked out of the conversation it just started.
   */
  authToken?: string;
}
