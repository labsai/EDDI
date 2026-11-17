/**
 * Starting a team session with a question from elsewhere in Workforce — Home's
 * composer with a team picked, or an @mention hand-off from a chat (T2.1,
 * T2.2). The board starts the discussion with it once, then drops it.
 *
 * Like a chat's first message (`@eddi/app-kit/lib/workforce-chat-start`), the
 * question travels in router state, never in the URL: a link can open a team's
 * board, but only the person typing in this app starts a (paid) discussion.
 */

export interface InitialTeamQuestion {
  /** The team the question is for — must match the board's `:boardId`. */
  groupId: string;
  text: string;
  /** The hand-off this question came from, to link the session back to it. */
  handoffId?: string;
}

export interface WorkforceTeamStartState {
  initialTeamQuestion: InitialTeamQuestion;
}

/** Where to navigate, and the state to carry, to start a session of `groupId` with `text`. */
export function workforceTeamStart(
  groupId: string,
  text: string,
  options: { version?: number | null; handoffId?: string } = {},
): { to: string; state: WorkforceTeamStartState } {
  const query = options.version ? `?version=${encodeURIComponent(String(options.version))}` : "";
  return {
    to: `/workforce/${encodeURIComponent(groupId)}${query}`,
    state: {
      initialTeamQuestion: {
        groupId,
        text,
        ...(options.handoffId ? { handoffId: options.handoffId } : {}),
      },
    },
  };
}

/** The team question in a location's state, or null when there is none (or it is malformed). */
export function readInitialTeamQuestion(state: unknown): InitialTeamQuestion | null {
  if (!state || typeof state !== "object") return null;
  const candidate = (state as { initialTeamQuestion?: unknown }).initialTeamQuestion;
  if (!candidate || typeof candidate !== "object") return null;
  const { groupId, text, handoffId } = candidate as { groupId?: unknown; text?: unknown; handoffId?: unknown };
  if (typeof groupId !== "string" || !groupId || typeof text !== "string" || !text.trim()) return null;
  return { groupId, text, ...(typeof handoffId === "string" && handoffId ? { handoffId } : {}) };
}
