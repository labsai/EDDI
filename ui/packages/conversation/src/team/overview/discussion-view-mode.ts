/**
 * How a group discussion is being read.
 *
 * - `transcript` — the prose turns, unchanged and still the default
 * - `overview` — the dashboard
 * - `split` — both at once
 *
 * Kept separate from `shared/view-mode.ts`, whose `ViewMode` is the
 * card/list switch for list pages. Sharing the storage key would have made a
 * user's agent-list preference decide how their discussions render.
 */
export type DiscussionViewMode = "transcript" | "overview" | "split";

export const DISCUSSION_VIEW_MODES: readonly DiscussionViewMode[] = ["transcript", "overview", "split"];

const STORAGE_KEY = "eddi-discussion-view";

function isMode(value: unknown): value is DiscussionViewMode {
  return value === "transcript" || value === "overview" || value === "split";
}

/**
 * The stored preference for one surface, defaulting to `transcript`.
 *
 * Per-surface (`group-detail`, `workforce-board`, …) rather than global: the
 * Manager's detail page and the Workforce board are different-shaped windows
 * onto the same data, and a split that reads well in one is cramped in the
 * other.
 *
 * `split` is returned as stored even on a narrow screen — it is a layout that
 * stacks rather than a mode that needs space, so there is nothing to fall back
 * from. Resolving it against the viewport here would also mean the preference
 * silently changed to something the user never picked.
 */
export function getStoredDiscussionView(surface: string): DiscussionViewMode {
  try {
    const stored = localStorage.getItem(`${STORAGE_KEY}-${surface}`);
    if (isMode(stored)) return stored;
  } catch {
    // Private mode, blocked site data, or SSR — the default is correct.
  }
  return "transcript";
}

export function setStoredDiscussionView(surface: string, mode: DiscussionViewMode): void {
  try {
    localStorage.setItem(`${STORAGE_KEY}-${surface}`, mode);
  } catch {
    // A preference that cannot be saved is not worth failing a render over.
  }
}
