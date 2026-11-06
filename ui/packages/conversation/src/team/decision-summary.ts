import type { DecisionRecord } from "@eddi/core/groups";

/** One sentence of a decision summary: an i18n key, its English fallback, its values. */
export type SummarySentence = [key: string, fallback: string, values: Record<string, string | number>];

/** `NegotiationEngine.METHOD_ARBITRATION` — a ruling, which has no winner by design. */
const METHOD_ARBITRATION = "arbitration";

/**
 * What a discussion decided, in one or two plain sentences (T2.1: votes and
 * decisions surfaced plainly) — "The team voted, and “Ship it” won." —
 * before the card's detail (tally, ballots, minority report).
 *
 * Read only from the record's typed fields (`type`, `winner`, `method`,
 * `dissents`); the engine-specific `outcome` and `tally` stay with
 * `describeDecision`. A winner is named only when the record has one: an
 * agreement's `winner` is a proposal id, never shown.
 */
export function decisionSummary(decision: DecisionRecord): SummarySentence[] {
  const winner = decision.winner?.trim() || null;
  const out: SummarySentence[] = [];
  switch (decision.type) {
    case "VOTE":
      out.push(
        winner
          ? ["groups.decisionPlain.voteWon", "The team voted, and “{{winner}}” won.", { winner }]
          : ["groups.decisionPlain.voteNoWinner", "The team voted, but no option won.", {}],
      );
      break;
    case "VERDICT":
      if (decision.method === METHOD_ARBITRATION) {
        out.push(["groups.decisionPlain.arbitration", "An arbitrator decided. The ruling is below.", {}]);
      } else {
        out.push(
          winner
            ? ["groups.decisionPlain.verdictWinner", "The judge ruled for {{winner}}.", { winner }]
            : ["groups.decisionPlain.verdictTie", "The judge called it a tie.", {}],
        );
      }
      break;
    case "AGREEMENT":
      out.push(["groups.decisionPlain.agreement", "The members reached an agreement.", {}]);
      break;
    case "AWARD":
      out.push(
        winner
          ? ["groups.decisionPlain.award", "The work went to {{winner}}.", { winner }]
          : ["groups.decisionPlain.awardNone", "Nobody was given the work.", {}],
      );
      break;
    default:
      out.push(["groups.decisionPlain.none", "The team didn't reach a clear decision.", {}]);
  }
  const dissents = decision.dissents?.length ?? 0;
  if (dissents > 0) {
    // Worded without a plural form, so every locale needs one string.
    out.push(["groups.decisionPlain.dissents", "Members who disagreed: {{n}}.", { n: dissents }]);
  }
  return out;
}
