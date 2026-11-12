/* ──────────────────────────────────────────────
   AgentConversation — state and reducer

   The state of one 1:1 conversation with an agent as a client shows it, and the
   only function that changes it. Moved from the Chat UI's chat-store (phase 3b
   of the frontend unification plan), where every rule below was learned; the
   controller (`agent-conversation.ts`) dispatches these actions, and a host may
   dispatch the composer's own (attachments, secret mode, the requested field).

   The reducer is generic over the state: a host that keeps more beside it (the
   Chat UI's embedding config) passes its whole state through, and every case
   spreads what it does not own.
   ────────────────────────────────────────────── */

import type {
  ApprovalStatus,
  AttachmentResult,
  ConversationState,
  InputField,
  OutputImage,
  QuickReply,
} from "@eddi/core";

/** An attachment a sent message shows (name, type, size, a local preview). */
export interface MessageAttachment {
  fileName: string;
  mimeType?: string;
  sizeBytes?: number;
  /** Object URL for an inline image preview, revoked when the message goes. */
  previewUrl?: string;
  /** False when the file was stored but is too large to inline for the model. */
  forwardableInline?: boolean;
}

/** A guardrail that fired on a reply: why its text is what it is. Never the screened text. */
export interface GuardrailNotice {
  type: string;
  phase: string;
  action: string;
}

/** One message of the transcript (the user's, the agent's, or a notice). */
export interface ConversationMessage {
  id: string;
  role: "user" | "agent";
  content: string;
  timestamp: number;
  /** True while the agent is still streaming tokens. */
  isStreaming?: boolean;
  /**
   * `"notice"` marks text the CLIENT wrote (a failed send, an undo that did
   * not work, a cancelled request) as opposed to something the agent said. It
   * renders as a system notice, not as an agent bubble.
   */
  kind?: "notice";
  /** `image` output items, rendered by the client rather than as markdown. */
  images?: OutputImage[];
  /**
   * The conversation step this message belongs to, when the source said so —
   * set for transcripts rebuilt from a full snapshot. Messages of a live turn
   * leave it unset and are counted from the nearest one that has it
   * (`stepIndexes` in @eddi/core). Reply ratings are keyed by it.
   */
  stepIndex?: number;
  /** Files sent with a user message. */
  attachments?: MessageAttachment[];
  /** Guardrails that fired on an agent reply. */
  guardrails?: GuardrailNotice[];
  /** The reply ended in an error the client appended. */
  isError?: boolean;
}

/** Everything the controller owns about one conversation. */
export interface AgentConversationState {
  messages: ConversationMessage[];
  conversationId: string | null;
  conversationState: ConversationState | null;
  /**
   * Why an ENDED conversation ended, when the backend said (snapshot
   * `endReason`). Null while the conversation is not ended, or when no reason
   * is known.
   */
  endReason: string | null;
  quickReplies: QuickReply[];
  isProcessing: boolean;
  isThinking: boolean;
  /**
   * True between a `cascade_escalation` event and the next token. The cascade
   * has given up on a cheaper model and moved to a more capable one; an end
   * user sees only that the agent is working harder, never the model,
   * confidence or cost.
   */
  isEscalating: boolean;
  /**
   * Name of the tool the agent is running right now, from the live `tool_call`
   * event, or null. There is no "tool finished" event, so resumed output is the
   * completion signal: the next token clears it, as does anything that ends the
   * turn. Transient by design — it drives the status line only.
   */
  activeTool: string | null;
  /**
   * isProcessing is also raised by undo, redo, retry and cancel, which use it
   * as the re-entrancy guard. Those are not the agent composing a reply, so
   * this flag keeps the typing dots and the Stop button off for them. It falls
   * with isProcessing.
   */
  historyBusy: boolean;
  /**
   * The connection to a streamed turn dropped after the turn was accepted, and
   * the conversation is being re-read until that turn settles on the server
   * (`reconnectDelaysMs`). The turn is still the user's: Stop still stops it.
   */
  reconnecting: boolean;
  undoAvailable: boolean;
  redoAvailable: boolean;
  /** Set when the backend requests a specific input field (e.g. password). */
  activeInputField: InputField | null;
  /** Set when the user toggles secret mode on the composer. */
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
  /** The conversation could not be opened; the host offers a retry. */
  startFailed: boolean;
  /**
   * The agent the opened conversation belongs to, from its snapshot. Undefined
   * until a conversation that names one has been read (a managed route names an
   * intent, not an agent); null when it named none.
   */
  openedAgentId: string | null | undefined;
}

export const initialConversationState: AgentConversationState = {
  messages: [],
  conversationId: null,
  conversationState: null,
  endReason: null,
  quickReplies: [],
  isProcessing: false,
  isThinking: false,
  isEscalating: false,
  activeTool: null,
  historyBusy: false,
  reconnecting: false,
  undoAvailable: false,
  redoAvailable: false,
  activeInputField: null,
  isSecretMode: false,
  pendingAttachments: [],
  approvalStatus: null,
  restoreDraft: null,
  startFailed: false,
  openedAgentId: undefined,
};

export type ConversationAction =
  | { type: "SET_CONVERSATION_ID"; id: string | null }
  | {
      type: "SET_CONVERSATION_STATE";
      state: ConversationState;
      /**
       * The snapshot's `endReason`, from a read that carries one (`null` when
       * it had none). Omit it from sources that never do — the trimmed `done`
       * payload — so a reason already learned is kept.
       */
      endReason?: string | null;
    }
  | { type: "ADD_MESSAGE"; message: ConversationMessage }
  | { type: "ADD_SNAPSHOT_MESSAGE"; message: ConversationMessage }
  | { type: "APPEND_TO_LAST_AGENT"; token: string }
  | { type: "FINISH_STREAMING" }
  | { type: "SET_QUICK_REPLIES"; replies: QuickReply[] }
  | { type: "SET_PROCESSING"; value: boolean }
  | { type: "SET_HISTORY_BUSY" }
  | { type: "SET_RECONNECTING"; value: boolean }
  | { type: "SET_THINKING"; value: boolean }
  | { type: "SET_ESCALATING"; value: boolean }
  | { type: "SET_ACTIVE_TOOL"; tool: string | null }
  | { type: "SET_UNDO_REDO"; undoAvailable: boolean; redoAvailable: boolean }
  | { type: "REMOVE_EMPTY_STREAMING_MESSAGE" }
  | { type: "REPLACE_MESSAGES"; messages: ConversationMessage[] }
  | { type: "CLEAR_MESSAGES" }
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
  | { type: "RECONCILE_LAST_AGENT"; content: string }
  | { type: "ADD_GUARDRAIL"; notice: GuardrailNotice }
  | { type: "SET_START_FAILED"; value: boolean }
  | { type: "SET_OPENED_AGENT"; agentId: string | null | undefined };

const ACTION_TYPES: ReadonlySet<string> = new Set<ConversationAction["type"]>([
  "SET_CONVERSATION_ID",
  "SET_CONVERSATION_STATE",
  "ADD_MESSAGE",
  "ADD_SNAPSHOT_MESSAGE",
  "APPEND_TO_LAST_AGENT",
  "FINISH_STREAMING",
  "SET_QUICK_REPLIES",
  "SET_PROCESSING",
  "SET_HISTORY_BUSY",
  "SET_RECONNECTING",
  "SET_THINKING",
  "SET_ESCALATING",
  "SET_ACTIVE_TOOL",
  "SET_UNDO_REDO",
  "REMOVE_EMPTY_STREAMING_MESSAGE",
  "REPLACE_MESSAGES",
  "CLEAR_MESSAGES",
  "SET_INPUT_FIELD",
  "CLEAR_INPUT_FIELD",
  "TOGGLE_SECRET_MODE",
  "ADD_ATTACHMENT",
  "REMOVE_ATTACHMENT",
  "CLEAR_ATTACHMENTS",
  "SET_APPROVAL_STATUS",
  "WITHDRAW_LAST_USER_MESSAGE",
  "CLEAR_RESTORE_DRAFT",
  "RECONCILE_LAST_AGENT",
  "ADD_GUARDRAIL",
  "SET_START_FAILED",
  "SET_OPENED_AGENT",
]);

/** Whether an action is one this reducer handles (a host routes the rest to its own). */
export function isConversationAction(action: { type: string }): action is ConversationAction {
  return ACTION_TYPES.has(action.type);
}

/**
 * Index of the agent bubble currently being streamed into.
 *
 * Position is not a safe proxy: a local notice (an upload failure, a cap
 * warning) can be appended while a turn is in flight, after which "the last
 * message" is the notice and every subsequent token would land in it.
 * Falls back to a trailing agent message so a finished bubble is still
 * reconcilable.
 */
function streamingIndex(messages: ConversationMessage[]): number {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m && m.role === "agent" && m.isStreaming) return i;
  }
  const last = messages.length - 1;
  return last >= 0 && messages[last]?.role === "agent" ? last : -1;
}

/** Replace the message at `index` (which must exist) with `patch` applied. */
function patchAt(messages: ConversationMessage[], index: number, patch: Partial<ConversationMessage>): ConversationMessage[] {
  const next = [...messages];
  next[index] = { ...(next[index] as ConversationMessage), ...patch };
  return next;
}

export function conversationReducer<S extends AgentConversationState>(state: S, action: ConversationAction): S {
  switch (action.type) {
    case "SET_CONVERSATION_ID":
      return { ...state, conversationId: action.id };

    case "SET_CONVERSATION_STATE":
      return {
        ...state,
        conversationState: action.state,
        // A reason only ever describes an ENDED conversation.
        endReason:
          action.state !== "ENDED" ? null : action.endReason !== undefined ? action.endReason : state.endReason,
      };

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
      while (start > 0 && state.messages[start - 1]?.role === "agent") start -= 1;
      if (start > 0 && state.messages[start - 1]?.role === "user") start -= 1;

      for (let i = start; i < state.messages.length; i++) {
        const existingMessage = state.messages[i] as ConversationMessage;
        // Compare like with like: a user echo may only suppress a user bubble.
        if (existingMessage.role !== action.message.role) continue;
        const existing = existingMessage.content.trim();
        // Equality, or containment as a blank-line-delimited segment: the
        // `done` handler joins a step's whole output list into ONE bubble,
        // while this path receives one entry at a time, so the two producers
        // would otherwise never compare equal for a multi-entry step.
        if (existing === incoming || existing.split("\n\n").some((seg) => seg.trim() === incoming)) {
          return state;
        }
      }
      return { ...state, messages: [...state.messages, action.message] };
    }

    case "APPEND_TO_LAST_AGENT": {
      const idx = streamingIndex(state.messages);
      if (idx === -1) return state;
      const current = state.messages[idx] as ConversationMessage;
      return { ...state, messages: patchAt(state.messages, idx, { content: current.content + action.token }) };
    }

    case "FINISH_STREAMING": {
      const idx = streamingIndex(state.messages);
      const msgs = idx !== -1 ? patchAt(state.messages, idx, { isStreaming: false }) : [...state.messages];
      // Clearing escalation here rather than at each call site is what makes it
      // safe across every way a turn can end — done, error, stop-generating, a
      // stream that closed without a done event, and a HITL pause all funnel
      // through this action.
      return {
        ...state,
        messages: msgs,
        isProcessing: false,
        historyBusy: false,
        reconnecting: false,
        isThinking: false,
        isEscalating: false,
        activeTool: null,
      };
    }

    case "SET_QUICK_REPLIES":
      return { ...state, quickReplies: action.replies };

    case "SET_PROCESSING":
      return action.value
        ? { ...state, isProcessing: true }
        : { ...state, isProcessing: false, historyBusy: false, reconnecting: false };

    case "SET_HISTORY_BUSY":
      return { ...state, historyBusy: true };

    case "SET_RECONNECTING":
      return state.reconnecting === action.value ? state : { ...state, reconnecting: action.value };

    // Both are dispatched unconditionally on every token, so bail out when the
    // value is unchanged rather than allocating a new state object (and
    // re-rendering every consumer) once per token.
    case "SET_THINKING":
      return state.isThinking === action.value ? state : { ...state, isThinking: action.value };

    case "SET_ESCALATING": {
      // An escalation abandons the model that was calling tools, so a tool
      // named before it is no longer what the agent is doing.
      const activeTool = action.value ? null : state.activeTool;
      return state.isEscalating === action.value && state.activeTool === activeTool
        ? state
        : { ...state, isEscalating: action.value, activeTool };
    }

    // Cleared on every token, so the same unchanged-value bail-out applies.
    case "SET_ACTIVE_TOOL":
      return state.activeTool === action.tool ? state : { ...state, activeTool: action.tool };

    case "CLEAR_MESSAGES":
      return {
        ...state,
        messages: [],
        conversationId: null,
        quickReplies: [],
        conversationState: null,
        endReason: null,
        // A cleared conversation is not processing anything. This used to be
        // lowered only as a side effect of the abandoned stream's
        // FINISH_STREAMING safety net, which meant New Conversation mid-stream
        // left the FRESH conversation with a disabled composer, a dead Enter
        // key and a Stop button for a turn that was not running.
        isProcessing: false,
        historyBusy: false,
        reconnecting: false,
        isThinking: false,
        isEscalating: false,
        activeTool: null,
        undoAvailable: false,
        redoAvailable: false,
        activeInputField: null,
        isSecretMode: false,
        pendingAttachments: [],
        approvalStatus: null,
        restoreDraft: null,
        startFailed: false,
      };

    case "SET_UNDO_REDO":
      return { ...state, undoAvailable: action.undoAvailable, redoAvailable: action.redoAvailable };

    case "REMOVE_EMPTY_STREAMING_MESSAGE": {
      // A turn dropped server-side leaves a placeholder agent bubble that
      // never received tokens; left in place it renders as "No response".
      const idx = streamingIndex(state.messages);
      if (idx === -1 || state.messages[idx]?.content !== "") return state;
      return { ...state, messages: state.messages.filter((_, i) => i !== idx) };
    }

    case "REPLACE_MESSAGES":
      return { ...state, messages: action.messages };

    case "SET_INPUT_FIELD":
      return { ...state, activeInputField: action.field };

    case "CLEAR_INPUT_FIELD":
      return { ...state, activeInputField: null };

    case "TOGGLE_SECRET_MODE":
      return { ...state, isSecretMode: !state.isSecretMode };

    case "ADD_ATTACHMENT":
      return { ...state, pendingAttachments: [...state.pendingAttachments, action.attachment] };

    case "REMOVE_ATTACHMENT":
      return {
        ...state,
        pendingAttachments: state.pendingAttachments.filter((a) => a.storageRef !== action.storageRef),
      };

    case "CLEAR_ATTACHMENTS":
      return { ...state, pendingAttachments: [] };

    case "SET_APPROVAL_STATUS":
      return { ...state, approvalStatus: action.status };

    case "WITHDRAW_LAST_USER_MESSAGE": {
      let msgs = [...state.messages];
      // Drop the empty agent placeholder this turn created, if any.
      const placeholder = streamingIndex(msgs);
      if (placeholder !== -1 && msgs[placeholder]?.content === "") {
        msgs = msgs.filter((_, i) => i !== placeholder);
      }

      // Target the EXACT turn. Falling back to "the most recent user message"
      // let a late-failing turn delete a newer, unrelated one.
      const targetIndex = action.messageId
        ? msgs.findIndex((m) => m.id === action.messageId)
        : msgs.map((m) => m.role).lastIndexOf("user");

      const restored = { ...state, messages: msgs };
      if (action.attachments?.length) {
        restored.pendingAttachments = [...state.pendingAttachments, ...action.attachments];
      }
      if (targetIndex === -1) return restored;

      const withdrawn = msgs[targetIndex] as ConversationMessage;
      restored.messages = msgs.filter((_, i) => i !== targetIndex);
      // A secret is never handed back as plain text: it goes back into the
      // composer with secret mode ON, so it stays masked and is re-sent with
      // `secretInput`. Dropping it instead (the earlier behaviour) made the
      // user retype a key the server had refused without ever seeing.
      if (action.wasSecret) {
        restored.restoreDraft = action.draft ?? null;
        restored.isSecretMode = action.draft ? true : state.isSecretMode;
        return restored;
      }
      restored.restoreDraft = action.draft ?? withdrawn.content;
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
      if (state.messages[idx]?.content.trim() === incoming) return state;
      return { ...state, messages: patchAt(state.messages, idx, { content: action.content }) };
    }

    case "ADD_GUARDRAIL": {
      // On the reply being streamed: the notice explains THAT reply's text.
      const idx = streamingIndex(state.messages);
      if (idx === -1) return state;
      const current = state.messages[idx] as ConversationMessage;
      return {
        ...state,
        messages: patchAt(state.messages, idx, { guardrails: [...(current.guardrails ?? []), action.notice] }),
      };
    }

    case "SET_START_FAILED":
      return state.startFailed === action.value ? state : { ...state, startFailed: action.value };

    case "SET_OPENED_AGENT":
      return { ...state, openedAgentId: action.agentId };

    default:
      return state;
  }
}
