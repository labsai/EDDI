import type { DiscussionStyle } from "@/lib/api/groups";

/**
 * The stacked sections of the overview dashboard.
 *
 * - `outcome` — the verdict / tally / agreement and the synthesised answer
 * - `phases` — the phase rail, the discussion's spine
 * - `roster` — "who thinks what", one card per member
 * - `matrix` — the members × phases grid
 * - `extras` — whatever the caller passes through (task board, negotiation
 *   ledger, artifacts, retro badges), rendered by components that already exist
 */
export type OverviewBand = "outcome" | "phases" | "roster" | "matrix" | "extras";

const DEFAULT_ORDER: readonly OverviewBand[] = ["outcome", "phases", "roster", "matrix", "extras"];

/**
 * Per-style band ordering.
 *
 * This is the whole of "works with every group chat, with differences for
 * each": one renderer, one data adapter, and a table deciding what leads.
 * Adding a style needs a row here at most — never a second renderer — and a
 * style this build has never heard of falls through to {@link DEFAULT_ORDER}
 * rather than rendering nothing.
 *
 * `outcome` leads almost everywhere because it is the answer the reader came
 * for. It costs nothing to put first: every band hides itself when it has no
 * data, so while a discussion is still running the rail is simply the first
 * thing on screen. The exceptions are the two styles whose *working surface*
 * is the point — a task force is its task board, a negotiation is its ledger —
 * where burying that under an empty outcome slot would be wrong.
 */
const ORDER_BY_STYLE: Partial<Record<DiscussionStyle, readonly OverviewBand[]>> = {
  // Positions matter more than progress: the reader wants the verdict, then
  // who argued what, and only then the stage the debate reached.
  DEBATE: ["outcome", "roster", "phases", "matrix", "extras"],
  // The challenger/defender split is the story; the rail is nearly uniform.
  DEVIL_ADVOCATE: ["outcome", "roster", "phases", "matrix", "extras"],
  // The board is the discussion. Roster last — members are assignees here, and
  // "who holds which task" is the task board's job, not a stance card's.
  TASK_FORCE: ["outcome", "extras", "phases", "matrix", "roster"],
  // The ledger of proposals and concessions is the substance; the roster's
  // stance lines read as each party's current position against it.
  NEGOTIATION: ["outcome", "extras", "roster", "phases", "matrix"],
  // Convergence is a per-phase score, so the rail carries the real signal —
  // and the roster is anonymised, which makes it the weakest band here.
  DELPHI: ["outcome", "phases", "roster", "matrix", "extras"],
};

/** The band order for a style, falling back to the default for unknown values. */
export function bandOrder(style: DiscussionStyle | string | null | undefined): readonly OverviewBand[] {
  if (!style) return DEFAULT_ORDER;
  return ORDER_BY_STYLE[style as DiscussionStyle] ?? DEFAULT_ORDER;
}

/**
 * Whether the roster should hide member identities.
 *
 * DELPHI's whole method is that members judge the argument rather than its
 * author — its later rounds render peers as "Anonymous" (`ContextScope.ANONYMOUS`).
 * A dashboard that helpfully named everyone next to their position would undo
 * that for the one human watching, which is precisely who the method is
 * protecting against anchoring.
 */
export function rosterIsAnonymous(style: DiscussionStyle | string | null | undefined): boolean {
  return style === "DELPHI";
}
