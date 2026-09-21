import type { DecisionRecord } from "@/lib/api/groups";
import { readableJsonObject, splitVerdictOutcome } from "@/components/groups/group-utils";

/**
 * A `DecisionRecord` laid out for reading — what the verdict card and the
 * markdown export both render from.
 *
 * `outcome` and `tally` are shaped by whichever engine produced the decision,
 * and treating them generically put engine internals on screen:
 *
 * | method                     | outcome                                   | tally                                                         |
 * |----------------------------|-------------------------------------------|---------------------------------------------------------------|
 * | `debate-judgment`          | `CON wins (PRO 6/10, CON 8/10) — <reasoning>` | `{PRO, CON}`                                              |
 * | `arbitration`              | the arbitrator's whole ruling, as prose   | none                                                          |
 * | `vote`, `vote+moderator-tiebreak` | one sentence                       | `{totals, ballots[], participants, validBallots, quorum, quorumReached}` |
 * | `negotiation`              | one sentence                              | `{proposalId, proposedBy, terms, signedAcceptances[], concessions[]}` |
 *
 * The verdict card printed a debate's entire markdown analysis as one plain
 * paragraph, and `JSON.stringify`'d the vote ballots and the concession ledger
 * into its score grid. Shapes are recognized by their keys rather than by
 * `method`, so a renamed method does not reopen the leak.
 */
export interface DecisionView {
  /** The finding in one line, when the outcome has one. */
  headline: string | null;
  /** An outcome that is a document rather than a sentence (an arbitration ruling), as markdown. */
  body: string | null;
  /** A winner a reader can use — not an agreement's proposal id. */
  showWinner: boolean;
  /** A VERDICT with no winner that is genuinely a tie — not an arbitration. */
  showTie: boolean;
  /** Label → value cells: side scores, or an option's weighted votes. */
  scores: [label: string, value: string][];
  /** Ballot turnout, for a vote. */
  ballots: { valid: number; participants: number } | null;
  /** The signed terms, for a negotiated agreement. */
  terms: string | null;
  concessions: { gaveUp: string; inReturnFor: string }[];
  /** Any remaining structured tally values this build has no layout for, as markdown. */
  details: string | null;
}

/** `NegotiationEngine.METHOD_ARBITRATION` — a ruling, which has no winner by design. */
const METHOD_ARBITRATION = "arbitration";

/** Longer than this and a single-line outcome is a document, not a headline. */
const HEADLINE_MAX_CHARS = 280;

const VOTE_TALLY_KEYS = ["totals", "ballots", "participants", "validBallots", "quorum", "quorumReached"];
const AGREEMENT_TALLY_KEYS = ["proposalId", "proposedBy", "terms", "signedAcceptances", "concessions"];

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

/** A tally cell's text. Numbers at a sane precision; `—` for a missing value. */
function cellText(value: unknown): string {
  if (typeof value === "number" && Number.isFinite(value)) {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
  if (value === null || value === undefined) return "—";
  return String(value);
}

export function describeDecision(decision: DecisionRecord): DecisionView {
  const outcome = decision.outcome?.trim() || null;
  const verdict = splitVerdictOutcome(outcome);

  let headline: string | null = null;
  let body: string | null = null;
  if (verdict) {
    // The reasoning after the headline is the synthesis body, which every
    // surface renders directly below this card.
    headline = verdict.headline;
  } else if (outcome && (outcome.includes("\n") || outcome.length > HEADLINE_MAX_CHARS)) {
    body = outcome;
  } else {
    headline = outcome;
  }

  const tally = isRecord(decision.tally) ? decision.tally : null;
  const consumed = new Set<string>();
  const scores: [string, string][] = [];
  let ballots: DecisionView["ballots"] = null;
  let terms: string | null = null;
  let concessions: DecisionView["concessions"] = [];
  let agreement = false;

  if (tally && isRecord(tally.totals)) {
    for (const [option, total] of Object.entries(tally.totals)) scores.push([option, cellText(total)]);
    // Each ballot is already on screen as its VOTE entry, and the losing
    // ballots' statements are the minority report.
    if (typeof tally.validBallots === "number" && typeof tally.participants === "number") {
      ballots = { valid: tally.validBallots, participants: tally.participants };
    }
    VOTE_TALLY_KEYS.forEach((key) => consumed.add(key));
  } else if (tally && (typeof tally.terms === "string" || Array.isArray(tally.concessions))) {
    agreement = true;
    terms = typeof tally.terms === "string" && tally.terms.trim() ? tally.terms.trim() : null;
    concessions = (Array.isArray(tally.concessions) ? tally.concessions : [])
      .filter(isRecord)
      .flatMap((c) =>
        typeof c.gaveUp === "string" && typeof c.inReturnFor === "string"
          ? [{ gaveUp: c.gaveUp, inReturnFor: c.inReturnFor }]
          : [],
      );
    // Proposal and participant ids, and the transcript indices of the signatures.
    AGREEMENT_TALLY_KEYS.forEach((key) => consumed.add(key));
  }

  const nested: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(tally ?? {})) {
    if (consumed.has(key)) continue;
    if (value !== null && typeof value === "object") nested[key] = value;
    else scores.push([key, cellText(value)]);
  }
  const details = Object.keys(nested).length > 0 ? readableJsonObject(nested) || null : null;

  // `NegotiationEngine` writes an agreement's outcome as the signed terms again,
  // prefixed with the proposal id and the proposer's AGENT ID ("Agreement on
  // proposal p3 (by 6aa82052…), signed by all 2 participants: <terms>"). With the
  // terms laid out on their own it says nothing new, and says it in ids.
  const restatesTerms = agreement && !!terms;

  return {
    headline: restatesTerms ? null : headline,
    body: restatesTerms ? null : body,
    // An agreement's `winner` is the accepted proposal's id ("p3").
    showWinner: !!decision.winner && decision.type !== "AGREEMENT",
    showTie: decision.type === "VERDICT" && !decision.winner && decision.method !== METHOD_ARBITRATION,
    scores,
    ballots,
    terms,
    concessions,
    details,
  };
}
