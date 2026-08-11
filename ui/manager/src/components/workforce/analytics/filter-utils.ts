import type { GroupConversationState } from "@/lib/api/groups";

// ─── Filter types ────────────────────────────────────────────────

export interface ActiveFilter {
  type: "outcome" | "style" | "date" | "agent";
  label: string;
  value: string;
}

// ─── Label helpers ───────────────────────────────────────────────

/**
 * English fallbacks for the group-conversation states. These are the LAST
 * resort, not the display path: every render site resolves `groups.state.<STATE>`
 * through i18next first (see {@link stateLabel}). Keeping the map means a state
 * this build does not have a translation for still shows a readable word rather
 * than a raw enum constant.
 */
const STATE_LABEL_FALLBACKS: Record<GroupConversationState, string> = {
  COMPLETED: "Completed",
  FAILED: "Failed",
  IN_PROGRESS: "In Progress",
  SYNTHESIZING: "Synthesizing",
  CREATED: "Created",
  CANCELLED: "Cancelled",
  AWAITING_APPROVAL: "Pending",
  AWAITING_HUMAN_INPUT: "Awaiting your turn",
  CLOSED: "Closed",
};

/** i18n key for a state, so every surface labels it the same way. */
export function stateLabelKey(s: GroupConversationState): string {
  return `groups.state.${s}`;
}

/** English fallback for a state — pass to `t()` as the default value. */
export function stateLabelFallback(s: GroupConversationState): string {
  return STATE_LABEL_FALLBACKS[s] ?? s;
}

/**
 * Localized label for a state. Takes `t` rather than calling `useTranslation`
 * because this module is a plain helper used from both components and
 * non-component code.
 */
export function stateLabel(
  s: GroupConversationState,
  t?: (key: string, fallback: string) => string,
): string {
  const fallback = stateLabelFallback(s);
  return t ? t(stateLabelKey(s), fallback) : fallback;
}

/**
 * Localized name for a discussion style. Re-exported so the analytics
 * filters and every other surface resolve the same key.
 */
export { styleLabel } from "@/lib/discussion-styles";
