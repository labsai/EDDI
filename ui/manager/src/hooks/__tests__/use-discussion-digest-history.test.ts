import { describe, expect, it } from "vitest";
import { buildDigest } from "@/hooks/use-discussion-digest";
import type {
  DiscussionPhase,
  GroupConversation,
  TranscriptEntry,
  TranscriptEntryType,
} from "@/lib/api/groups";

/**
 * The digest must describe the round it is asked about with that round's own
 * data, and must not take the round's QUESTION for a phase.
 */

const A = "agent-a";
const B = "agent-b";

function entry(
  agentId: string,
  phaseIndex: number,
  type: TranscriptEntryType = "OPINION",
  content: string | null = "Something.",
  phaseName: string | null = `Phase ${phaseIndex + 1}`,
): TranscriptEntry {
  return {
    speakerAgentId: agentId,
    speakerDisplayName: agentId,
    content,
    phaseIndex,
    phaseName,
    type,
    timestamp: "2026-09-22T10:00:00Z",
    errorReason: null,
    targetAgentId: null,
  };
}

/** Exactly what the backend stores for a round's question. */
const question = (text: string) => entry("user", 0, "QUESTION", text, "Question");

function conversation(overrides: Partial<GroupConversation> = {}): GroupConversation {
  return {
    id: "gc-1",
    groupId: "g-1",
    userId: "u-1",
    state: "COMPLETED",
    originalQuestion: "Q1?",
    transcript: [],
    memberConversationIds: {},
    currentPhaseIndex: 0,
    currentPhaseName: null,
    synthesizedAnswer: null,
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    retainedAgentIds: [],
    created: "2026-09-22T10:00:00.000Z",
    lastModified: "2026-09-22T10:05:00.000Z",
    ...overrides,
  } as GroupConversation;
}

const PHASES: DiscussionPhase[] = [
  {
    name: "Opinions",
    type: "OPINION",
    participants: "ALL",
    turnOrder: "PARALLEL",
    contextScope: "NONE",
    targetEachPeer: false,
    inputTemplate: null,
    repeats: 1,
  },
  {
    name: "Synthesis",
    type: "SYNTHESIS",
    participants: "MODERATOR",
    turnOrder: "SEQUENTIAL",
    contextScope: "FULL",
    targetEachPeer: false,
    inputTemplate: null,
    repeats: 1,
  },
];

describe("buildDigest — the round's QUESTION is not a phase (G2)", () => {
  it("names phase 0 after the phase that ran, not 'Question'", () => {
    const conv = conversation({
      transcript: [question("Q1?"), entry(A, 0, "OPINION", "Yes.", "Initial Opinions")],
    });
    expect(buildDigest(conv, undefined, PHASES).phases[0]?.name).toBe("Initial Opinions");
  });

  it("falls back to the configured name when only the question has run", () => {
    const conv = conversation({ state: "IN_PROGRESS", transcript: [question("Q1?")] });
    const d = buildDigest(conv, undefined, PHASES);
    expect(d.phases[0]?.name).toBe("Opinions");
  });

  it("does not count a phase as started because the question sits at its index", () => {
    const conv = conversation({ state: "FAILED", transcript: [question("Q1?")] });
    expect(buildDigest(conv, undefined, PHASES).phases[0]?.status).toBe("pending");
  });
});

describe("buildDigest — an earlier round uses its own data (G4)", () => {
  const twoRounds = () =>
    conversation({
      round: 2,
      roundStartTranscriptIndex: 4,
      transcript: [
        question("Q1?"),
        entry(A, 0, "OPINION", "Round one stance. More detail."),
        entry(B, 0, "OPINION", "B round one."),
        entry("mod", 1, "SYNTHESIS", "Round one conclusion."),
        question("Q2?"),
        entry(A, 0, "OPINION", "Round two stance."),
      ],
      memberStances: {
        [A]: { text: "Current stance, from round two.", llmGenerated: true, coveredContributions: 2, updated: "x" },
      },
      memberCosts: { [A]: 1.5, [B]: 0.5, "system:stance": 0.1 },
      synthesizedAnswer: "Round two conclusion.",
      decision: { type: "VERDICT", winner: "PRO", tally: null, method: "judge", dissents: [], decidedAtPhase: "Synthesis" },
    } as unknown as Partial<GroupConversation>);

  it("extracts round one's stance from round one's turns, as a quotation", () => {
    const d = buildDigest(twoRounds(), undefined, PHASES, undefined, null, 1);
    const a = d.members.find((m) => m.agentId === A)!;
    expect(a.stance).toBe("Round one stance.");
    expect(a.stanceIsQuote).toBe(true);
  });

  it("does not report the whole discussion's spend as round one's", () => {
    const d = buildDigest(twoRounds(), undefined, PHASES, undefined, null, 1);
    expect(d.totalCost).toBeNull();
    expect(d.members.every((m) => m.cost === null)).toBe(true);
  });

  it("shows round one's own synthesis and no later verdict", () => {
    const d = buildDigest(twoRounds(), undefined, PHASES, undefined, null, 1);
    expect(d.synthesizedAnswer).toBe("Round one conclusion.");
    expect(d.decision).toBeNull();
  });

  it("still uses the whole-discussion records for the newest round", () => {
    const d = buildDigest(twoRounds(), undefined, PHASES);
    expect(d.selectedRound).toBe(2);
    expect(d.members.find((m) => m.agentId === A)?.stance).toBe("Current stance, from round two.");
    expect(d.totalCost).toBeCloseTo(2.1);
    expect(d.synthesizedAnswer).toBe("Round two conclusion.");
    expect(d.decision?.type).toBe("VERDICT");
  });
});
