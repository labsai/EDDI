/* ──────────────────────────────────────────────
   React bindings: the controller's state and the shared strings
   ────────────────────────────────────────────── */

import { createContext, useContext, useSyncExternalStore, type ReactNode } from "react";
import type { AgentConversation } from "../controller/agent-conversation";
import type { AgentConversationState } from "../controller/state";
import { englishText, type Translate } from "../i18n/text";

/** The controller's state, re-rendering on every change. */
export function useAgentConversation(controller: AgentConversation): AgentConversationState {
  return useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot);
}

const TextContext = createContext<Translate>(englishText);

/**
 * The app's translator for every component below. Without it the components
 * render English. Pass a new function when the language changes (the Manager's
 * `t` from `useTranslation` does), so they re-render.
 */
export function ConversationTextProvider({ t, children }: { t: Translate; children: ReactNode }) {
  return <TextContext.Provider value={t}>{children}</TextContext.Provider>;
}

/** The translator the nearest provider gave, or English. */
export function useConversationText(): Translate {
  return useContext(TextContext);
}
