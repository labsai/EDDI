/* ──────────────────────────────────────────────
   EDDI Chat — State Management
   Lightweight Context + useReducer (no zustand).
   ────────────────────────────────────────────── */

import {
  createContext,
  useContext,
  useReducer,
  type Dispatch,
  type ReactNode,
} from "react";
import type { ChatMessage, QuickReply, ConversationState, ChatConfig, InputField } from "@/types";
import type { AttachmentResult } from "@/api/attachments-api";
import type { ApprovalStatus } from "@/api/hitl-api";

/* ─── State ───────────────────────────────────── */

export interface ChatState {
  messages: ChatMessage[];
  conversationId: string | null;
  conversationState: ConversationState | null;
  quickReplies: QuickReply[];
  isProcessing: boolean;
  isThinking: boolean;
  /**
   * True between a `cascade_escalation` event and the next token. The cascade
   * has given up on a cheaper model and moved to a more capable one; the user
   * sees only that the agent is working harder, never the model, confidence or
   * cost — those stay admin-side in EDDI-Manager.
   */
  isEscalating: boolean;
  undoAvailable: boolean;
  redoAvailable: boolean;
  agentName: string | null;
  config: ChatConfig;
  /** Set when the backend requests a specific input field (e.g. password). */
  activeInputField: InputField | null;
  /** Set when the user toggles the 🔒 secret mode on the chat input. */
  isSecretMode: boolean;
  /**
   * Files uploaded but not yet attached to a turn. They travel with the NEXT
   * message as attachment_N context entries — uploading alone does not send
   * them to the agent.
   */
  pendingAttachments: AttachmentResult[];
  /**
   * Set while the conversation is AWAITING_HUMAN. There is no push channel on
   * the 1:1 surface, so this is refreshed by polling approval-status.
   */
  approvalStatus: ApprovalStatus | null;
  /**
   * Text put back into the composer after the server refused the turn
   * (409 — never consumed). Null once the composer has picked it up.
   */
  restoreDraft: string | null;
}

const defaultConfig: ChatConfig = {
  theme: "dark",
  accentColor: "#113B92",
  showLogo: true,
  logoUrl: "/img/logo_eddi.png",
  title: "EDDI",
  placeholder: "Type a message...",
  enableStreaming: true,
  enableQuickReplies: true,
  enableMarkdown: true,
  enableMath: true,
  enableCodeHighlight: true,
  enableUndo: true,
  enableRedo: true,
  enableNewConversation: true,
  showAgentName: true,
};

export const initialState: ChatState = {
  messages: [],
  conversationId: null,
  conversationState: null,
  quickReplies: [],
  isProcessing: false,
  isThinking: false,
  isEscalating: false,
  undoAvailable: false,
  redoAvailable: false,
  agentName: null,
  config: defaultConfig,
  activeInputField: null,
  isSecretMode: false,
  pendingAttachments: [],
  approvalStatus: null,
  restoreDraft: null,
};

/* ─── Actions ─────────────────────────────────── */

export type ChatAction =
  | { type: "SET_CONVERSATION_ID"; id: string | null }
  | { type: "SET_CONVERSATION_STATE"; state: ConversationState }
  | { type: "ADD_MESSAGE"; message: ChatMessage }
  | { type: "ADD_SNAPSHOT_MESSAGE"; message: ChatMessage }
  | { type: "APPEND_TO_LAST_AGENT"; token: string }
  | { type: "FINISH_STREAMING" }
  | { type: "SET_QUICK_REPLIES"; replies: QuickReply[] }
  | { type: "SET_PROCESSING"; value: boolean }
  | { type: "SET_THINKING"; value: boolean }
  | { type: "SET_ESCALATING"; value: boolean }
  | { type: "SET_UNDO_REDO"; undoAvailable: boolean; redoAvailable: boolean }
  | { type: "REMOVE_EMPTY_STREAMING_MESSAGE" }
  | { type: "REPLACE_MESSAGES"; messages: ChatMessage[] }
  | { type: "SET_AGENT_NAME"; name: string | null }
  | { type: "CLEAR_MESSAGES" }
  | { type: "SET_CONFIG"; config: Partial<ChatConfig> }
  | { type: "SET_INPUT_FIELD"; field: InputField }
  | { type: "CLEAR_INPUT_FIELD" }
  | { type: "TOGGLE_SECRET_MODE" }
  | { type: "ADD_ATTACHMENT"; attachment: AttachmentResult }
  | { type: "REMOVE_ATTACHMENT"; storageRef: string }
  | { type: "CLEAR_ATTACHMENTS" }
  | { type: "SET_APPROVAL_STATUS"; status: ApprovalStatus | null }
  | {
      type: "WITHDRAW_LAST_USER_MESSAGE";
      /** Id of the exact message to withdraw — NOT merely the most recent one. */
      messageId?: string;
      /** The raw composer text. The bubble may be masked or carry 📎 lines. */
      draft?: string;
      /** Attachments staged for the refused turn, to put back. */
      attachments?: AttachmentResult[];
      /** True when the withdrawn turn was secret; its text must not be restored. */
      wasSecret?: boolean;
    }
  | { type: "CLEAR_RESTORE_DRAFT" }
  | { type: "RECONCILE_LAST_AGENT"; content: string };

/**
 * Index of the agent bubble currently being streamed into.
 *
 * Position is not a safe proxy: a local notice (an upload failure, a cap
 * warning) can be appended while a turn is in flight, after which "the last
 * message" is the notice and every subsequent token would land in it.
 * Falls back to a trailing agent message so a finished bubble is still
 * reconcilable.
 */
function streamingIndex(messages: ChatMessage[]): number {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "agent" && messages[i].isStreaming) return i;
  }
  const last = messages.length - 1;
  return last >= 0 && messages[last].role === "agent" ? last : -1;
}

/* ─── Reducer ─────────────────────────────────── */

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case "SET_CONVERSATION_ID":
      return { ...state, conversationId: action.id };

    case "SET_CONVERSATION_STATE":
      return { ...state, conversationState: action.state };

    case "ADD_MESSAGE":
      return { ...state, messages: [...state.messages, action.message] };

    case "ADD_SNAPSHOT_MESSAGE": {
      // Used ONLY by reads that deliberately revisit an already-rendered step
      // (retry, post-approval refresh).
      //
      // Matching is on agent-message CONTENT, because the text being re-read may
      // have first reached the transcript via the streaming `done` handler,
      // which stamps no identifier of any kind.
      //
      // The comparison is scoped to the TRAILING run of agent messages — those
      // after the last user bubble. Both real duplication cases put the
      // duplicate there, whereas a repeated utterance from an earlier turn is
      // always separated by an intervening user message. Scanning the whole
      // transcript swallowed legitimate repeats (a fallback said twice, a
      // re-asked prompt).
      const incoming = action.message.content.trim();
      if (!incoming) return state;

      // The window is the trailing turn: agent messages after the last user
      // bubble, plus that bubble itself. Including it matters because a
      // snapshot re-read also carries the user's own input, and a role-blind
      // window could never suppress that echo.
      let start = state.messages.length;
      while (start > 0 && state.messages[start - 1].role === "agent") start -= 1;
      if (start > 0 && state.messages[start - 1].role === "user") start -= 1;

      for (let i = start; i < state.messages.length; i++) {
        // Compare like with like: a user echo may only suppress a user bubble.
        if (state.messages[i].role !== action.message.role) continue;
        const existing = state.messages[i].content.trim();
        // Equality, or containment as a blank-line-delimited segment: the
        // `done` handler joins a step's whole output list into ONE bubble,
        // while this path receives one entry at a time, so the two producers
        // would otherwise never compare equal for a multi-entry step.
        if (
          existing === incoming ||
          existing.split("\n\n").some((seg) => seg.trim() === incoming)
        ) {
          return state;
        }
      }
      return { ...state, messages: [...state.messages, action.message] };
    }

    case "APPEND_TO_LAST_AGENT": {
      const idx = streamingIndex(state.messages);
      if (idx === -1) return state;
      const msgs = [...state.messages];
      msgs[idx] = { ...msgs[idx], content: msgs[idx].content + action.token };
      return { ...state, messages: msgs };
    }

    case "FINISH_STREAMING": {
      const idx = streamingIndex(state.messages);
      const msgs = [...state.messages];
      if (idx !== -1) msgs[idx] = { ...msgs[idx], isStreaming: false };
      // Clearing escalation here rather than at each call site is what makes it
      // safe across every way a turn can end — done, error, stop-generating, a
      // stream that closed without a done event, and a HITL pause all funnel
      // through this action.
      return {
        ...state,
        messages: msgs,
        isProcessing: false,
        isThinking: false,
        isEscalating: false,
      };
    }

    case "SET_QUICK_REPLIES":
      return { ...state, quickReplies: action.replies };

    case "SET_PROCESSING":
      return { ...state, isProcessing: action.value };

    // Both are dispatched unconditionally on every token, so bail out when the
    // value is unchanged rather than allocating a new state object (and
    // re-rendering every consumer) once per token.
    case "SET_THINKING":
      return state.isThinking === action.value
        ? state
        : { ...state, isThinking: action.value };

    case "SET_ESCALATING":
      return state.isEscalating === action.value
        ? state
        : { ...state, isEscalating: action.value };

    case "CLEAR_MESSAGES":
      return {
        ...state,
        messages: [],
        conversationId: null,
        quickReplies: [],
        conversationState: null,
        // A cleared conversation is not processing anything. This used to be
        // lowered only as a side effect of the abandoned stream's
        // FINISH_STREAMING safety net, which meant New Conversation mid-stream
        // left the FRESH conversation with a disabled composer, a dead Enter
        // key and a Stop button for a turn that was not running.
        isProcessing: false,
        isThinking: false,
        isEscalating: false,
        undoAvailable: false,
        redoAvailable: false,
        activeInputField: null,
        isSecretMode: false,
        pendingAttachments: [],
        approvalStatus: null,
        restoreDraft: null,
      };

    case "SET_UNDO_REDO":
      return { ...state, undoAvailable: action.undoAvailable, redoAvailable: action.redoAvailable };

    case "REMOVE_EMPTY_STREAMING_MESSAGE": {
      // A turn dropped server-side leaves a placeholder agent bubble that
      // never received tokens; left in place it renders as "No response".
      const idx = streamingIndex(state.messages);
      if (idx === -1 || state.messages[idx].content !== "") return state;
      return { ...state, messages: state.messages.filter((_, i) => i !== idx) };
    }

    case "REPLACE_MESSAGES":
      return { ...state, messages: action.messages };

    case "SET_AGENT_NAME":
      return { ...state, agentName: action.name };

    case "SET_CONFIG":
      return { ...state, config: { ...state.config, ...action.config } };

    case "SET_INPUT_FIELD":
      return { ...state, activeInputField: action.field };

    case "CLEAR_INPUT_FIELD":
      return { ...state, activeInputField: null };

    case "TOGGLE_SECRET_MODE":
      return { ...state, isSecretMode: !state.isSecretMode };

    case "ADD_ATTACHMENT":
      return {
        ...state,
        pendingAttachments: [...state.pendingAttachments, action.attachment],
      };

    case "REMOVE_ATTACHMENT":
      return {
        ...state,
        pendingAttachments: state.pendingAttachments.filter(
          (a) => a.storageRef !== action.storageRef,
        ),
      };

    case "CLEAR_ATTACHMENTS":
      return { ...state, pendingAttachments: [] };

    case "SET_APPROVAL_STATUS":
      return { ...state, approvalStatus: action.status };

    case "WITHDRAW_LAST_USER_MESSAGE": {
      let msgs = [...state.messages];
      // Drop the empty agent placeholder this turn created, if any.
      const placeholder = streamingIndex(msgs);
      if (placeholder !== -1 && msgs[placeholder].content === "") {
        msgs = msgs.filter((_, i) => i !== placeholder);
      }

      // Target the EXACT turn. Falling back to "the most recent user message"
      // let a late-failing turn delete a newer, unrelated one.
      const targetIndex = action.messageId
        ? msgs.findIndex((m) => m.id === action.messageId)
        : msgs.map((m) => m.role).lastIndexOf("user");

      const restored = { ...state, messages: msgs };
      if (action.attachments?.length) {
        restored.pendingAttachments = [
          ...state.pendingAttachments,
          ...action.attachments,
        ];
      }
      if (targetIndex === -1) return restored;

      const withdrawn = msgs[targetIndex];
      restored.messages = msgs.filter((_, i) => i !== targetIndex);
      // A secret is never handed back as plain text — the composer that would
      // receive it is unmasked, and the secret marking would be lost.
      restored.restoreDraft = action.wasSecret
        ? null
        : action.draft ?? withdrawn.content;
      return restored;
    }

    case "CLEAR_RESTORE_DRAFT":
      return { ...state, restoreDraft: null };

    case "RECONCILE_LAST_AGENT": {
      // The final snapshot is authoritative. responseValidation `fallback`
      // substitutes its own text AFTER tokens were streamed, so what the user
      // is looking at may be a response the backend decided not to give.
      const incoming = action.content.trim();
      if (!incoming) return state;

      const idx = streamingIndex(state.messages);
      if (idx === -1) return state;
      if (state.messages[idx].content.trim() === incoming) return state;

      const msgs = [...state.messages];
      msgs[idx] = { ...msgs[idx], content: action.content };
      return { ...state, messages: msgs };
    }

    default:
      return state;
  }
}

/* ─── Context ─────────────────────────────────── */

const ChatStateContext = createContext<ChatState>(initialState);
const ChatDispatchContext = createContext<Dispatch<ChatAction>>(() => {});

export function ChatProvider({
  children,
  config,
}: {
  children: ReactNode;
  config?: Partial<ChatConfig>;
}) {
  const [state, dispatch] = useReducer(chatReducer, {
    ...initialState,
    config: { ...defaultConfig, ...config },
  });

  return (
    <ChatStateContext.Provider value={state}>
      <ChatDispatchContext.Provider value={dispatch}>
        {children}
      </ChatDispatchContext.Provider>
    </ChatStateContext.Provider>
  );
}

export function useChatState(): ChatState {
  return useContext(ChatStateContext);
}

export function useChatDispatch(): Dispatch<ChatAction> {
  return useContext(ChatDispatchContext);
}
