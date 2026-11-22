/* ──────────────────────────────────────────────
   The conversation a subtree renders

   The composer and the requested field stage attachments, toggle secret mode
   and pick up a restored draft — state the controller owns. A host provides
   the controller's state and dispatch here once (the Chat UI from its
   ChatProvider), and the components below read it without props drilling.
   ────────────────────────────────────────────── */

import {
  createContext,
  useContext,
  useLayoutEffect,
  useMemo,
  useRef,
  type MutableRefObject,
  type ReactNode,
} from "react";
import { initialConversationState, type AgentConversationState, type ConversationAction } from "../controller/state";

export interface ConversationContextValue {
  state: AgentConversationState;
  dispatch: (action: ConversationAction) => void;
  /**
   * The conversation the composer was last mounted for. Owned by the provider
   * rather than by the composer, so that it outlives one composer instance:
   * the requested field replaces the composer and a fresh one mounts after it,
   * and an upload started by an instance that has since unmounted must still be
   * judged against the conversation the composer is on NOW.
   */
  composerConversation: MutableRefObject<string | null | undefined> | null;
}

const ConversationContext = createContext<ConversationContextValue>({
  state: initialConversationState,
  dispatch: () => {},
  // Outside a provider each composer keeps its own record.
  composerConversation: null,
});

/** Provide the conversation (state and dispatch) the components below act on. */
export function ConversationProvider({
  state,
  dispatch,
  children,
}: {
  state: AgentConversationState;
  dispatch: (action: ConversationAction) => void;
  children: ReactNode;
}) {
  const composerConversation = useRef<string | null | undefined>(undefined);
  // Follow a conversation change even while no composer is mounted (the
  // requested field replaces it): an upload started in the old conversation
  // that settles meanwhile must be judged against the one on screen, not
  // staged into it. Only a CHANGE writes — on mount the composer's own prop
  // is the record, so a host whose composer is given its id another way is
  // left alone. Both write in the commit, before any continuation can run.
  const lastConversationId = useRef(state.conversationId);
  useLayoutEffect(() => {
    if (lastConversationId.current === state.conversationId) return;
    lastConversationId.current = state.conversationId;
    composerConversation.current = state.conversationId;
  }, [state.conversationId]);
  const value = useMemo(() => ({ state, dispatch, composerConversation }), [state, dispatch]);
  return (
    <ConversationContext.Provider value={value}>{children}</ConversationContext.Provider>
  );
}

export function useConversationContext(): ConversationContextValue {
  return useContext(ConversationContext);
}
