/**
 * Handing a first message from Workforce Home (or a starter) to the chat.
 *
 * The text travels in router state, never in the URL. A URL can be crafted and
 * sent to somebody; router state can only be set by the app itself, so a link
 * still only preselects an agent (the chat's deep-link rule, `chat-panel.tsx`)
 * and nothing is said on anyone's behalf without them typing it here.
 */

export interface InitialChatMessage {
  /** The agent the message is for — must match the chat's `?agentId=`. */
  agentId: string;
  text: string;
}

export interface WorkforceChatStartState {
  initialMessage: InitialChatMessage;
}

/** Where to navigate, and the state to carry, to start a chat with `text`. */
export function workforceChatStart(agentId: string, text: string): { to: string; state: WorkforceChatStartState } {
  return {
    to: `/workforce/chat?agentId=${encodeURIComponent(agentId)}`,
    state: { initialMessage: { agentId, text } },
  };
}

/** The initial message in a location's state, or null when there is none (or it is malformed). */
export function readInitialChatMessage(state: unknown): InitialChatMessage | null {
  if (!state || typeof state !== "object") return null;
  const candidate = (state as { initialMessage?: unknown }).initialMessage;
  if (!candidate || typeof candidate !== "object") return null;
  const { agentId, text } = candidate as { agentId?: unknown; text?: unknown };
  if (typeof agentId !== "string" || typeof text !== "string" || !agentId || !text.trim()) return null;
  return { agentId, text };
}
