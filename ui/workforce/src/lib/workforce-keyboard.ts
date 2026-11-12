/**
 * The router state that asks Home for a new chat (⌘⇧O, "New chat" in the
 * quick switcher): Home focuses its composer when it arrives with it.
 */
export const NEW_CHAT_STATE = { newChat: true } as const;

/** Whether router state asks for a new chat. */
export function isNewChatState(state: unknown): boolean {
  return typeof state === "object" && state !== null && (state as { newChat?: unknown }).newChat === true;
}

/** Ask the quick switcher to open, e.g. from a button: `window.dispatchEvent(new Event(OPEN_SWITCHER_EVENT))`. */
export const OPEN_SWITCHER_EVENT = "workforce:open-switcher";
