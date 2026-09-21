import { describe, it, expect } from "vitest";
import { describeDecision } from "@/lib/group-decision";
import type { DecisionRecord } from "@/lib/api/groups";

/**
 * Decisions as EDDI's engines build them — `DebateVerdictParser`,
 * `NegotiationEngine` (agreement and arbitration) and `VoteTallyEngine`.
 */
const base: DecisionRecord = {
  type: "VERDICT",
  outcome: null,
  winner: null,
  tally: null,
  dissents: [],
  method: null,
  decidedAtPhase: "Judgment",
};

const noJson = (text: string | null) => expect(text ?? "").not.toMatch(/[[{]\s*"|"\w+"\s*:/);

describe("describeDecision", () => {
  it("shows only a debate's headline — the reasoning is the synthesis below", () => {
    const view = describeDecision({
      ...base,
      winner: "CON",
      method: "debate-judgment",
      tally: { PRO: 6, CON: 8 },
      outcome: "CON wins (PRO 6/10, CON 8/10) — ### Verdict\nCON built the stronger case.",
    });
    expect(view.headline).toBe("CON wins (PRO 6/10, CON 8/10)");
    expect(view.body).toBeNull();
    expect(view.scores).toEqual([["PRO", "6"], ["CON", "8"]]);
  });

  it("calls a winnerless debate verdict a tie", () => {
    expect(describeDecision({ ...base, method: "debate-judgment", outcome: "Tie (PRO 7/10, CON 7/10)" }).showTie).toBe(true);
  });

  it("does not call an arbitration a tie, and keeps its ruling as a document", () => {
    const ruling = "## Ruling\nPartyA keeps support; PartyB gets the longer term.\n\nBoth concede pricing.";
    const view = describeDecision({ ...base, method: "arbitration", outcome: ruling });
    expect(view.showTie).toBe(false);
    expect(view.headline).toBeNull();
    expect(view.body).toBe(ruling);
  });

  it("lays out a vote's weighted totals and turnout, never its ballot records", () => {
    const view = describeDecision({
      ...base,
      type: "VOTE",
      winner: "Ship it",
      method: "vote",
      outcome: '"Ship it" wins with 2.00 of 3.00 weighted votes.',
      tally: {
        totals: { "Ship it": 2, "Hold it": 1 },
        ballots: [
          { agentId: "a1", votes: ["Ship it"], confidence: 0.9, weight: 1, statement: "ready" },
          { agentId: "a2", votes: ["Hold it"], weight: 1 },
        ],
        participants: 3,
        validBallots: 3,
        quorum: 0.5,
        quorumReached: true,
      },
    });
    expect(view.headline).toBe('"Ship it" wins with 2.00 of 3.00 weighted votes.');
    expect(view.scores).toEqual([["Ship it", "2"], ["Hold it", "1"]]);
    expect(view.ballots).toEqual({ valid: 3, participants: 3 });
    expect(view.details).toBeNull();
    view.scores.forEach(([, value]) => noJson(value));
  });

  it("lays out a negotiated agreement's terms and concessions, never its ids", () => {
    const view = describeDecision({
      ...base,
      type: "AGREEMENT",
      winner: "p2",
      method: "negotiation",
      outcome: "Agreement on proposal p2 (by agent-a), signed by all 2 participants: 55/45 — 1 concession(s) on the ledger.",
      tally: {
        proposalId: "p2",
        proposedBy: "agent-a",
        terms: "55/45 with support included",
        signedAcceptances: [4, 5],
        concessions: [{ by: "agent-b", gaveUp: "weekend support", inReturnFor: "a longer term" }],
      },
    });
    expect(view.terms).toBe("55/45 with support included");
    // The outcome restates the terms in ids, and the winner IS an id.
    expect(view.headline).toBeNull();
    expect(view.body).toBeNull();
    expect(view.showWinner).toBe(false);
    expect(view.concessions).toEqual([{ gaveUp: "weekend support", inReturnFor: "a longer term" }]);
    expect(view.scores).toEqual([]);
    expect(view.details).toBeNull();
  });

  it("renders any other structured tally value as a list rather than JSON", () => {
    const view = describeDecision({ ...base, tally: { note: "n/a", detail: { reviewer: "Ada", rounds: [1, 2] } } });
    expect(view.scores).toEqual([["note", "n/a"]]);
    expect(view.details).toContain("**Reviewer**: Ada");
    noJson(view.details);
  });
});
