/* ──────────────────────────────────────────────
   The conversation a subtree renders

   The composer and the requested field stage attachments, toggle secret mode
   and pick up a restored draft — state the controller owns. A host provides
   the controller's state and dispatch here once (the Chat UI from its
   ChatProvider), and the components below read it without props drilling.
   ────────────────────────────────────────────── */

import { createContext, useContext, useMemo, useRef, type MutableRefObject, type ReactNode } from "react";
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
  const value = useMemo(() => ({ state, dispatch, composerConversation }), [state, dispatch]);
  return (
    <ConversationContext.Provider value={value}>{children}</ConversationContext.Provider>
  );
}

export function useConversationContext(): ConversationContextValue {
  return useContext(ConversationContext);
}
