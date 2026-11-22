import { expect } from "vitest";
import type { DecisionRecord, GroupConversation, TranscriptEntry, TranscriptEntryType } from "../lib/api/groups";

/*
 * The fixtures of the group rendering matrix, shared by the Manager's half
 * (ui/manager/src/components/groups/__tests__/group-rendering-matrix.test.tsx:
 * the transcript card, the discussion transcript, the 1:1 chat, the verdict
 * card) and Workforce's (ui/workforce/src/components/workforce/__tests__/
 * group-rendering-matrix.test.tsx: the board, the history viewer, the export,
 * the context card). One list, so a new message shape reaches both apps.
 */

/**
 * Every message and every decision a group discussion can produce, rendered
 * through every surface that shows one.
 *
 * The fixtures are what EDDI stores, not what a renderer happens to handle:
 * the JSON contracts come from the prompts in `DiscussionStylePresets`,
 * `VoteTallyEngine`, `TaskBidEngine` and `NegotiationEngine`, the decisions
 * from `DebateVerdictParser`, `NegotiationEngine` and `VoteTallyEngine`, and
 * the verification sheet, failure placeholder and debate outcome from real
 * stored conversations.
 *
 * This file exists because each of those shapes was fixed on one surface and
 * left raw on another — a verdict fixed in the group transcript still printed
 * as JSON in the chat, a task plan the Manager listed reached the board as a
 * code block. A new surface or a new contract belongs here.
 */

export const AT = "2026-09-14T10:00:00Z";

export function entry(
  type: TranscriptEntryType,
  content: string | null,
  extra: Partial<TranscriptEntry> = {},
): TranscriptEntry {
  return {
    speakerAgentId: "agent-1",
    speakerDisplayName: "Member One",
    type,
    content,
    timestamp: AT,
    phaseIndex: 0,
    phaseName: "Phase",
    errorReason: null,
    targetAgentId: null,
    ...extra,
  };
}

export const DEBATE_REASONING =
  "### Verdict\nCON built the stronger case.\n\n### Teaching notes\n- **Unit economics** matter.";

export const VERDICT_JSON = JSON.stringify({ winner: "CON", scores: { PRO: 6, CON: 8 }, reasoning: DEBATE_REASONING });

const PLAN_FENCED =
  "```json\n[\n" +
  '  {"subject": "Facility Assessment", "description": "Audit the site", "assignedTo": "Energy Engineer", "priority": 1},\n' +
  '  {"subject": "Financial Model", "description": "Build the NPV", "assignedTo": "CFO", "priority": 2}\n' +
  "]\n```";

const VERIFICATION_FENCED =
  "```json\n[\n" +
  '  {"subject": "Facility Assessment", "passed": false, "feedback": "RESULT is empty."},\n' +
  '  {"subject": "Financial Model", "passed": true, "feedback": "Solid."}\n' +
  "]\n```";

export const FAILURE = "[Agent failed to produce output — conversation entered ERROR state]";

export interface MessageCase {
  name: string;
  entry: TranscriptEntry;
  /** Text a reader must be able to see. */
  facts: string[];
}

export const MESSAGES: MessageCase[] = [
  { name: "ROUND_TABLE opinion", entry: entry("OPINION", "## Where I stand\nWe should **pilot first**."), facts: ["Where I stand", "pilot first"] },
  { name: "PEER_REVIEW critique", entry: entry("CRITIQUE", "The draft **understates** migration cost."), facts: ["understates"] },
  { name: "PEER_REVIEW revision", entry: entry("REVISION", "Revised: budget raised to €2M."), facts: ["budget raised"] },
  { name: "DEVIL_ADVOCATE challenge", entry: entry("CHALLENGE", "What if the vendor **folds**?"), facts: ["vendor"] },
  { name: "DEVIL_ADVOCATE defense", entry: entry("DEFENSE", "We hold escrow for that case."), facts: ["escrow"] },
  {
    name: "DELPHI convergence",
    // Exactly as `PhaseExecutionEngine.recordConvergence` writes it: no speaker
    // id at all. Seen live — `hashColor(undefined)` took the transcript down.
    entry: entry("CONVERGENCE", "Agreement score 0,72 (threshold 0,80) — Positions have settled.", {
      speakerAgentId: undefined as unknown as string,
      speakerDisplayName: "System",
    }),
    facts: ["Agreement score 0,72"],
  },
  { name: "DEBATE argument", entry: entry("ARGUMENT", "**PRO** — price is the #1 purchase driver."), facts: ["#1 purchase driver"] },
  { name: "DEBATE rebuttal", entry: entry("REBUTTAL", "That contradicts the opening claim."), facts: ["contradicts"] },
  { name: "DEBATE verdict (bare JSON)", entry: entry("SYNTHESIS", VERDICT_JSON), facts: ["CON built the stronger case", "Unit economics"] },
  { name: "DEBATE verdict (fenced JSON)", entry: entry("SYNTHESIS", "```json\n" + JSON.stringify({ winner: "TIE", scores: { PRO: 7, CON: 7 }, reasoning: "It was close." }) + "\n```"), facts: ["It was close."] },
  { name: "TASK_FORCE plan", entry: entry("PLAN", PLAN_FENCED), facts: ["Facility Assessment", "Financial Model", "Audit the site"] },
  { name: "TASK_FORCE task result", entry: entry("TASK_RESULT", "Audit complete: **12 ECMs** found."), facts: ["12 ECMs"] },
  { name: "TASK_FORCE failed task", entry: entry("TASK_RESULT", "[ERROR] Task 'Financial Model' failed: timeout"), facts: ["failed: timeout"] },
  { name: "TASK_FORCE verification (legacy JSON)", entry: entry("VERIFICATION", VERIFICATION_FENCED), facts: ["Facility Assessment", "RESULT is empty."] },
  {
    name: "TASK_FORCE verification (formatted)",
    entry: entry("VERIFICATION", "## Task Verification Results\n\n✅ **Financial Model**: Passed\nSolid.\n\n❌ **Facility Assessment**: Failed\nEmpty."),
    facts: ["Financial Model", "Facility Assessment"],
  },
  { name: "NEGOTIATION proposal", entry: entry("PROPOSAL", "60/40 split, support included."), facts: ["60/40 split"] },
  {
    name: "NEGOTIATION bargain",
    entry: entry("BARGAIN", '{"accept": null, "proposal": {"terms": "55/45 with support"}, "concessions": [{"gaveUp": "weekend support", "inReturnFor": "a longer term"}]} It balances both sides.'),
    facts: ["55/45 with support", "weekend support", "It balances both sides."],
  },
  { name: "VOTE ballot", entry: entry("VOTE", '{"vote": "Ship it", "confidence": 0.8, "statement": "Ready to go."}'), facts: ["Ship it", "80% confident", "Ready to go."] },
  {
    name: "BID sheet",
    entry: entry("BID", '{"bids": [{"subject": "Write the migration", "confidence": 0.9, "estimatedComplexity": "M", "rationale": "I own the schema"}]}'),
    facts: ["Write the migration", "90% confident", "I own the schema"],
  },
  {
    name: "RETRO harvest",
    entry: entry("RETRO", '{"lessons": [{"lesson": "Cap debate rounds", "context": "long debates"}]}'),
    facts: ["Cap debate rounds", "long debates"],
  },
  { name: "DISSENT", entry: entry("DISSENT", "The migration cost is understated."), facts: ["understated"] },
  { name: "FACILITATION", entry: entry("FACILITATION", "Facilitator recruited Security Reviewer — coverage gap."), facts: ["Security Reviewer"] },
  { name: "FOLLOW_UP", entry: entry("FOLLOW_UP", "Following up: what about **latency**?"), facts: ["latency"] },
  { name: "HUMAN_INPUT", entry: entry("HUMAN_INPUT", "As director I prefer **Germany**."), facts: ["Germany"] },
  { name: "response envelope", entry: entry("OPINION", '{"output":[{"type":"text","text":"Envelope text"}]}'), facts: ["Envelope text"] },
  { name: "ABSTAINED", entry: entry("ABSTAINED", null), facts: ["Declined to add anything new this round."] },
  { name: "ERROR", entry: entry("ERROR", null, { errorReason: "Model timed out" }), facts: ["Model timed out"] },
];

export const FAILED_MEMBER = entry("OPINION", FAILURE, { speakerDisplayName: "Broken Member" });

/** Nothing on screen may look like the wire format. */
export function expectReadable(text: string, { allowHeadings = false }: { allowHeadings?: boolean } = {}) {
  expect(text, "a JSON key reached the reader").not.toMatch(/"[A-Za-z_]+"\s*:/);
  expect(text, "a code fence reached the reader").not.toContain("```");
  expect(text, "an escaped newline reached the reader").not.toContain("\\n");
  expect(text, "the failure placeholder reached the reader as an answer").not.toContain("Agent failed to produce output");
  if (!allowHeadings) {
    expect(text, "a markdown heading marker reached the reader").not.toMatch(/(?:^|\s)#{1,6} [A-Za-z]/);
  }
}

export function conversation(transcript: TranscriptEntry[], extra: Partial<GroupConversation> = {}): GroupConversation {
  return {
    id: "gconv-matrix",
    groupId: "group-matrix",
    userId: "user-1",
    state: "COMPLETED",
    originalQuestion: "Which market first?",
    transcript,
    memberConversationIds: {},
    currentPhaseIndex: 0,
    currentPhaseName: "Phase",
    synthesizedAnswer: null,
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    retainedAgentIds: [],
    created: AT,
    lastModified: AT,
    ...extra,
  } as GroupConversation;
}

// ─── Decisions ───────────────────────────────────────────────────

export interface DecisionCase {
  name: string;
  decision: DecisionRecord;
  facts: string[];
  tie: boolean;
  /** Text that must NOT reach the reader — engine ids, mostly. */
  forbid?: string[];
}

export const DEBATE_DECISION: DecisionRecord = {
  type: "VERDICT",
  winner: "CON",
  tally: { PRO: 6, CON: 8 },
  method: "debate-judgment",
  outcome: `CON wins (PRO 6/10, CON 8/10) — ${DEBATE_REASONING}`,
  dissents: [],
  decidedAtPhase: "Judgment",
  raw: VERDICT_JSON,
};

const BALLOTS = [
  { agentId: "a1", votes: ["Ship it"], confidence: 0.9, weight: 1, statement: "ready" },
  { agentId: "a2", votes: ["Hold it"], weight: 1, statement: "needs QA" },
];

export const DECISIONS: DecisionCase[] = [
  { name: "debate verdict", decision: DEBATE_DECISION, facts: ["CON wins (PRO 6/10, CON 8/10)", "Winner: CON"], tie: false },
  {
    name: "debate tie",
    decision: { ...DEBATE_DECISION, winner: null, tally: { PRO: 7, CON: 7 }, outcome: "Tie (PRO 7/10, CON 7/10) — Evenly matched." },
    facts: ["Tie (PRO 7/10, CON 7/10)"],
    tie: true,
  },
  {
    name: "arbitration",
    decision: {
      type: "VERDICT", winner: null, tally: null, method: "arbitration", dissents: [], decidedAtPhase: "Arbitration",
      outcome: "## Ruling\nPartyA keeps support; PartyB gets the **longer term**.",
    },
    facts: ["Ruling", "longer term"],
    tie: false,
  },
  {
    name: "vote",
    decision: {
      type: "VOTE", winner: "Ship it", method: "vote", decidedAtPhase: "Ballot",
      outcome: '"Ship it" wins with 2.00 of 3.00 weighted votes.',
      tally: { totals: { "Ship it": 2, "Hold it": 1 }, ballots: BALLOTS, participants: 3, validBallots: 3, quorum: 0.5, quorumReached: true },
      dissents: [{ agentId: "a2", displayName: "QA Lead", position: "needs QA" }],
    },
    facts: ["Ship it", "Hold it", "3 of 3 ballots valid", "needs QA"],
    tie: false,
  },
  {
    name: "vote tiebreak",
    decision: {
      type: "VOTE", winner: "Hold it", method: "vote+moderator-tiebreak", decidedAtPhase: "Ballot", raw: "Hold it",
      outcome: '"Hold it" chosen by the moderator (tie break).',
      tally: { totals: { "Ship it": 1, "Hold it": 1 }, ballots: BALLOTS, participants: 2, validBallots: 2, quorum: 0.5, quorumReached: true },
      dissents: [],
    },
    facts: ["chosen by the moderator", "2 of 2 ballots valid"],
    tie: false,
  },
  {
    name: "negotiated agreement",
    decision: {
      type: "AGREEMENT", winner: "p2", method: "negotiation", decidedAtPhase: "Bargaining", dissents: [],
      outcome: "Agreement on proposal p2 (by agent-a), signed by all 2 participants: 55/45 with support included — 1 concession(s) on the ledger.",
      tally: {
        proposalId: "p2", proposedBy: "agent-a", terms: "55/45 with support included", signedAcceptances: [4, 5],
        concessions: [{ by: "agent-b", gaveUp: "weekend support", inReturnFor: "a longer term" }],
      },
    },
    facts: ["Agreed terms", "55/45 with support included", "weekend support", "in return for a longer term"],
    tie: false,
    // Seen live: "Winner: p3" and "Agreement on proposal p3 (by 6aa82052…)".
    forbid: ["agent-a", "Winner: p2", "Agreement on proposal"],
  },
];

/** A decision fixture by name, so reordering `DECISIONS` cannot silently swap one. */
export function decisionCase(name: string): DecisionCase {
  const found = DECISIONS.find((d) => d.name === name);
  if (!found) throw new Error(`unknown decision case: ${name}`);
  return found;
}
