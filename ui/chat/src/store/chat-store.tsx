/* ──────────────────────────────────────────────
   EDDI Chat — State Management

   The conversation itself is an `AgentConversation` from @eddi/conversation:
   the turn state machine, its state and its reducer are shared with every
   other agent surface. This provider owns one controller, and keeps beside it
   what is the widget's own: the embedding config, the agent's name, the review
   notice and the rating profile. `useChatState()` reads both as one object and
   `useChatDispatch()` routes each action to its owner, so components keep one
   way to read and one way to write.
   ────────────────────────────────────────────── */

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useReducer,
  useState,
  type Dispatch,
  type MutableRefObject,
  type ReactNode,
} from "react";
import {
  AgentConversation,
  ConversationProvider,
  ConversationTextProvider,
  conversationReducer,
  initialConversationState,
  isConversationAction,
  useAgentConversation,
  useConversationContext,
  type AgentConversationState,
  type AgentTransport,
  type ConversationAction,
} from "@eddi/conversation";
import type { ChatConfig } from "@/types";
import { agentAppearanceLayer, brandConfigLayer } from "@/api/brand";
import type { RatingProfile } from "@eddi/core";
import { t } from "@/i18n";

/* ─── State ───────────────────────────────────── */

/** What the widget keeps beside the conversation. */
export interface ChatHostState {
  agentName: string | null;
  /**
   * Shown before the first message when the agent's maintainers may read this
   * conversation. Null when they may not.
   */
  reviewNotice: string | null;
  /**
   * How the agent asks for ratings (profile `rating`), or null when it does
   * not — then no rating control is rendered at all.
   */
  ratingProfile: RatingProfile | null;
  config: ChatConfig;
}

export type ChatState = AgentConversationState & ChatHostState;

/**
 * The title a header shows when nobody overrode it and nothing is branded. A
 * branded deployment's product name takes its place (brandConfigLayer).
 */
export const DEFAULT_TITLE = "EDDI";

/** The composer placeholder when nobody overrode it (shown translated). */
export const DEFAULT_PLACEHOLDER = "Type a message...";

/**
 * The built-in defaults: the lowest layer of the config (precedence in
 * src/api/brand.ts). `accentColor` and `logoUrl` are deliberately unset — the
 * stylesheet's own accent tokens apply, and the header resolves the logo from
 * the brand (brandLogoUrl) — so that setting either is always an override.
 */
const defaultConfig: ChatConfig = {
  theme: "dark",
  showLogo: true,
  title: DEFAULT_TITLE,
  placeholder: DEFAULT_PLACEHOLDER,
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
  ...initialConversationState,
  agentName: null,
  reviewNotice: null,
  ratingProfile: null,
  config: defaultConfig,
};

/* ─── Actions ─────────────────────────────────── */

type HostAction =
  | { type: "SET_AGENT_NAME"; name: string | null }
  | { type: "SET_REVIEW_NOTICE"; notice: string | null }
  | { type: "SET_RATING_PROFILE"; profile: RatingProfile | null }
  | { type: "SET_CONFIG"; config: Partial<ChatConfig> };

export type ChatAction = ConversationAction | HostAction;

function hostReducer<S extends ChatHostState>(state: S, action: HostAction): S {
  switch (action.type) {
    case "SET_AGENT_NAME":
      return { ...state, agentName: action.name };
    case "SET_REVIEW_NOTICE":
      return { ...state, reviewNotice: action.notice };
    case "SET_RATING_PROFILE":
      return { ...state, ratingProfile: action.profile };
    case "SET_CONFIG":
      return { ...state, config: { ...state.config, ...action.config } };
    default:
      return state;
  }
}

/* ─── Reducer ─────────────────────────────────── */

/** The whole widget state, one action at a time: the conversation's rules, then the widget's. */
export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  return isConversationAction(action) ? conversationReducer(state, action) : hostReducer(state, action);
}

/* ─── Context ─────────────────────────────────── */

/** Until ChatWidget names the route, nothing can be opened or sent. */
const NO_ROUTE: AgentTransport = {
  mode: "conversation",
  read: () => Promise.reject(new Error("no conversation route yet")),
  undo: () => Promise.resolve(),
  redo: () => Promise.resolve(),
  rerun: () => Promise.resolve(),
  cancel: () => Promise.resolve(),
};

const ChatStateContext = createContext<ChatState>(initialState);
const ChatDispatchContext = createContext<Dispatch<ChatAction>>(() => {});
const ChatControllerContext = createContext<AgentConversation | null>(null);

export function ChatProvider({
  children,
  config,
}: {
  children: ReactNode;
  config?: Partial<ChatConfig>;
}) {
  const [controller] = useState(() => new AgentConversation({ transport: NO_ROUTE, t }));
  const conversation = useAgentConversation(controller);
  // Lowest to highest: built-in defaults, the deployment brand, the agent's
  // appearance (a seam, empty today), the embedder's config. URL parameters
  // come last, dispatched by ChatWidget on mount. Computed once, at mount.
  const [host, hostDispatch] = useReducer(hostReducer<ChatHostState>, config, (embedderConfig) => ({
    agentName: null,
    reviewNotice: null,
    ratingProfile: null,
    config: { ...defaultConfig, ...brandConfigLayer(), ...agentAppearanceLayer(), ...embedderConfig },
  }));
  const state = useMemo<ChatState>(() => ({ ...conversation, ...host }), [conversation, host]);
  const dispatch = useCallback<Dispatch<ChatAction>>(
    (action) => (isConversationAction(action) ? controller.dispatch(action) : hostDispatch(action)),
    [controller],
  );

  return (
    <ChatControllerContext.Provider value={controller}>
      <ChatStateContext.Provider value={state}>
        <ChatDispatchContext.Provider value={dispatch}>
          <ConversationProvider state={conversation} dispatch={controller.dispatch}>
            <ConversationTextProvider t={t}>{children}</ConversationTextProvider>
          </ConversationProvider>
        </ChatDispatchContext.Provider>
      </ChatStateContext.Provider>
    </ChatControllerContext.Provider>
  );
}

/** The provider's AgentConversation. */
export function useChatController(): AgentConversation {
  const controller = useContext(ChatControllerContext);
  if (!controller) throw new Error("useChatController needs a <ChatProvider>");
  return controller;
}

/**
 * The provider's record of the composer's current conversation, or null
 * outside a provider, where the caller keeps its own.
 */
export function useComposerConversationRef(): MutableRefObject<string | null | undefined> | null {
  return useConversationContext().composerConversation;
}

export function useChatState(): ChatState {
  return useContext(ChatStateContext);
}

export function useChatDispatch(): Dispatch<ChatAction> {
  return useContext(ChatDispatchContext);
}
