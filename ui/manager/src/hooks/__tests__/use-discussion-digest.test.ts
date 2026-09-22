import { describe, expect, it } from "vitest";
import { buildDigest } from "@/hooks/use-discussion-digest";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";
import type {
  DiscussionPhase,
  GroupConversation,
  TranscriptEntry,
  TranscriptEntryType,
} from "@/lib/api/groups";

/**
 * Tests for the adapter that normalises a live SSE stream and a persisted
 * conversation into one model.
 *
 * The cases worth guarding are the ones where the two sources disagree, or
 * where a plausible-looking simplification would quietly state something false
 * — `absent` collapsing into `pending`, a member's nested-group costs being
 * dropped because they are not keyed by agent id, a stale live map masking
 * persisted figures.
 */

const A = "agent-architect";
const B = "agent-security";
const MOD = "agent-moderator";

function entry(
  agentId: string,
  phaseIndex: number,
  type: TranscriptEntryType = "OPINION",
  content = "Something.",
): TranscriptEntry {
  return {
    speakerAgentId: agentId,
    speakerDisplayName: agentId,
    content,
    phaseIndex,
    phaseName: `Phase ${phaseIndex + 1}`,
    type,
    timestamp: "2026-09-22T10:00:00Z",
    errorReason: null,
    targetAgentId: null,
  };
}

function conversation(overrides: Partial<GroupConversation> = {}): GroupConversation {
  return {
    id: "gc-1",
    groupId: "g-1",
    userId: "u-1",
    state: "COMPLETED",
    originalQuestion: "Should we migrate to pgvector?",
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

function stream(overrides: Partial<GroupStreamState> = {}): GroupStreamState {
  return {
    isStreaming: true,
    conversationId: "gc-1",
    state: "IN_PROGRESS",
    transcript: [],
    currentPhase: null,
    activeSpeakers: new Set(),
    synthesizedAnswer: null,
    decision: null,
    convergence: new Map(),
    error: null,
    errorKind: null,
    startedAt: "2026-09-22T10:00:00.000Z",
    taskPlan: null,
    taskVerifications: new Map(),
    tasksInProgress: new Set(),
    tasksCompleted: new Set(),
    hitlPause: null,
    hitlResume: null,
    cancelInfo: null,
    humanInputRequest: null,
    retroRecorded: [],
    artifactUpdates: [],
    memberCosts: new Map(),
    stances: new Map(),
    ...overrides,
  } as GroupStreamState;
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

describe("buildDigest — phases", () => {
  it("shows configured phases that have not been reached yet", () => {
    const digest = buildDigest(conversation({ state: "IN_PROGRESS" }), undefined, PHASES);
    expect(digest.phases.map((p) => p.name)).toEqual(["Opinions", "Synthesis"]);
  });

  it("prefers the transcript's phase name over the config's", () => {
    // A facilitator can diverge the runtime phase list from the configured one
    // (I12); the transcript records what actually ran.
    const conv = conversation({
      transcript: [{ ...entry(A, 0), phaseName: "Opinions (extended)" }],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.phases[0]?.name).toBe("Opinions (extended)");
  });

  it("derives phases from the transcript when no config is available", () => {
    // The Workforce history viewer's case: it opens a stored conversation and
    // never fetches the group.
    const conv = conversation({ transcript: [entry(A, 0), entry(A, 1)] });
    const digest = buildDigest(conv);
    expect(digest.phases).toHaveLength(2);
  });

  it("marks a terminal discussion's unreached phases pending, not done", () => {
    const conv = conversation({
      state: "CANCELLED",
      currentPhaseIndex: 1,
      transcript: [entry(A, 0)],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.phases[0]?.status).toBe("done");
    expect(digest.phases[1]?.status).toBe("pending");
  });

  it("counts only member contributions, not bookkeeping rows", () => {
    const conv = conversation({
      transcript: [entry(A, 0), entry(A, 0, "CONVERGENCE"), entry(A, 0, "QUESTION")],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.phases[0]?.entryCount).toBe(1);
    expect(digest.phases[0]?.spokenBy).toEqual([A]);
  });
});

describe("buildDigest — matrix", () => {
  it("distinguishes a member excluded from a phase from one still expected", () => {
    // The point of the distinction: a MODERATOR-only phase must not read as
    // "everyone went quiet".
    const conv = conversation({
      state: "IN_PROGRESS",
      currentPhaseIndex: 1,
      transcript: [entry(A, 0), entry(B, 0)],
    });
    const digest = buildDigest(conv, stream({ isStreaming: false }), PHASES);
    const phase1 = digest.phases.findIndex((p) => p.index === 1);

    // Phase 1's selector is MODERATOR — unknown membership, so pending, not a
    // false "absent".
    expect(digest.matrix[A]?.[phase1]?.kind).toBe("pending");
    // Phase 0 is done for both.
    expect(digest.matrix[A]?.[0]?.kind).toBe("spoke");
    expect(digest.matrix[B]?.[0]?.kind).toBe("spoke");
  });

  it("marks a member absent from a completed phase they never spoke in", () => {
    const conv = conversation({
      state: "COMPLETED",
      transcript: [entry(A, 0), entry(MOD, 1, "SYNTHESIS")],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.matrix[A]?.[1]?.kind).toBe("absent");
    expect(digest.matrix[MOD]?.[1]?.kind).toBe("spoke");
  });

  it("surfaces a failed turn over a successful one in the same cell", () => {
    const conv = conversation({
      transcript: [entry(A, 0), entry(A, 0, "ERROR", "timed out")],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.matrix[A]?.[0]?.kind).toBe("failed");
  });

  it("surfaces a dissent over an ordinary contribution", () => {
    const conv = conversation({
      transcript: [entry(A, 0), entry(A, 0, "DISSENT", "I disagree.")],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.matrix[A]?.[0]?.kind).toBe("dissent");
  });

  it("only reads as abstained when every entry in the cell is one", () => {
    const both = conversation({
      transcript: [entry(A, 0, "ABSTAINED", "Nothing to add."), entry(A, 0)],
    });
    expect(buildDigest(both, undefined, PHASES).matrix[A]?.[0]?.kind).toBe("spoke");

    const only = conversation({ transcript: [entry(A, 0, "ABSTAINED", "Nothing to add.")] });
    expect(buildDigest(only, undefined, PHASES).matrix[A]?.[0]?.kind).toBe("abstained");
  });

  it("distinguishes a dropped turn from an exclusion", () => {
    // Phase 0's selector is ALL, so a finished phase with nothing from this
    // member means their turn was dropped (maxTurns, a cost ceiling, a
    // SYNTHESIZE_NOW jump) — not that they were never part of it.
    const conv = conversation({
      state: "COMPLETED",
      transcript: [entry(A, 0), entry(MOD, 1, "SYNTHESIS")],
    });
    const digest = buildDigest(conv, undefined, PHASES, { [B]: "Security" });

    expect(digest.matrix[B]?.[0]?.kind).toBe("silent");
    // Phase 1's selector is MODERATOR — unknown for B, so `absent` is honest.
    expect(digest.matrix[B]?.[1]?.kind).toBe("absent");
  });

  it("is dense over members × phases", () => {
    const conv = conversation({ transcript: [entry(A, 0), entry(B, 1)] });
    const digest = buildDigest(conv, undefined, PHASES);
    for (const member of digest.members) {
      expect(digest.matrix[member.agentId]).toHaveLength(digest.phases.length);
    }
  });
});

describe("buildDigest — continuation rounds", () => {
  it("does not merge a previous round's turns into this round's phases", () => {
    // A continuation round restarts phaseIndex at 0, so bucketing the whole
    // transcript by phase index put round 1's turns in round 2's cells.
    const conv = conversation({
      round: 2,
      roundStartTranscriptIndex: 2,
      transcript: [
        entry(A, 0, "OPINION", "Round one."),
        entry(A, 1, "ERROR", "round one failure"),
        entry(B, 0, "OPINION", "Round two."),
      ],
    });
    const digest = buildDigest(conv, undefined, PHASES);

    expect(digest.totalEntries).toBe(1);
    expect(digest.members.map((m) => m.agentId)).toEqual([B]);
    // Round 1's ERROR must not mark round 2's cell failed.
    expect(digest.matrix[B]?.[0]?.kind).toBe("spoke");
  });

  it("leaves a first round untouched", () => {
    const conv = conversation({
      roundStartTranscriptIndex: 0,
      transcript: [entry(A, 0), entry(B, 0)],
    });
    expect(buildDigest(conv, undefined, PHASES).totalEntries).toBe(2);
  });

  it("ignores an out-of-range round start rather than blanking the view", () => {
    const conv = conversation({
      roundStartTranscriptIndex: 99,
      transcript: [entry(A, 0)],
    });
    expect(buildDigest(conv, undefined, PHASES).totalEntries).toBe(1);
  });
});

describe("buildDigest — headline counts", () => {
  it("counts turns with the same filter the phase rail uses", () => {
    // Counting raw rows put QUESTION/CONVERGENCE/FACILITATION in the headline,
    // so it disagreed with the rail directly beneath it.
    const conv = conversation({
      transcript: [
        entry(A, 0, "QUESTION", "The user's question."),
        entry(A, 0, "OPINION", "A real turn."),
        entry(A, 0, "CONVERGENCE", "score 0.8"),
      ],
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.totalEntries).toBe(1);
    expect(digest.phases[0]?.entryCount).toBe(1);
  });
});

describe("buildDigest — cost", () => {
  it("sums every ledger key belonging to one member", () => {
    // A nested GROUP member is keyed agentId:childConversationId, one key per
    // child discussion — a plain lookup would report only the last one.
    const conv = conversation({
      transcript: [entry(A, 0)],
      memberCosts: { [A]: 0.1, [`${A}:child-1`]: 0.2, [`${A}:child-2`]: 0.3 },
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.members.find((m) => m.agentId === A)?.cost).toBeCloseTo(0.6);
  });

  it("does not attribute another agent whose id merely shares a prefix", () => {
    const conv = conversation({
      transcript: [entry("agent-a", 0)],
      memberCosts: { "agent-a": 1, "agent-abc": 99 },
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.members.find((m) => m.agentId === "agent-a")?.cost).toBe(1);
  });

  it("counts system spend in the total but attributes it to no member", () => {
    const conv = conversation({
      transcript: [entry(A, 0)],
      memberCosts: { [A]: 0.5, "system:stance:x:3": 0.25 },
    });
    const digest = buildDigest(conv, undefined, PHASES);
    expect(digest.totalCost).toBeCloseTo(0.75);
    expect(digest.members.find((m) => m.agentId === A)?.cost).toBe(0.5);
  });

  it("reports null rather than zero when nothing was attributed", () => {
    // An unpriced LLM config reports no cost at all; "$0.00" would read as
    // "this was free" instead of "this was not measured".
    const digest = buildDigest(conversation({ transcript: [entry(A, 0)] }), undefined, PHASES);
    expect(digest.totalCost).toBeNull();
    expect(digest.members[0]?.cost).toBeNull();
  });

  it("keeps persisted keys the live stream has not re-announced", () => {
    // A stream carries only what it announced THIS session. Swapping the maps
    // dropped an earlier round's system keys and every member yet to speak —
    // pressing Continue on a $4.10 discussion showed $0.02.
    const conv = conversation({
      transcript: [entry(A, 0), entry(B, 0)],
      memberCosts: { [A]: 4.0, "system:summarizer:full:12": 0.1 },
    });
    const live = stream({ memberCosts: new Map([[B, 0.02]]), transcript: [entry(A, 0), entry(B, 0)] });

    const digest = buildDigest(conv, live, PHASES);
    expect(digest.totalCost).toBeCloseTo(4.12);
    expect(digest.members.find((m) => m.agentId === A)?.cost).toBeCloseTo(4.0);
    expect(digest.members.find((m) => m.agentId === B)?.cost).toBeCloseTo(0.02);
  });

  it("prefers the live ledger while streaming", () => {
    const conv = conversation({ transcript: [entry(A, 0)], memberCosts: { [A]: 0.1 } });
    const live = stream({ memberCosts: new Map([[A, 0.9]]), transcript: [entry(A, 0)] });
    expect(buildDigest(conv, live, PHASES).totalCost).toBeCloseTo(0.9);
  });

  it("falls back to persisted figures when the live ledger is still empty", () => {
    // A discussion whose members carry no pricing emits no cost frame at all;
    // an empty live map must not mask an earlier round's persisted total.
    const conv = conversation({ transcript: [entry(A, 0)], memberCosts: { [A]: 0.4 } });
    const live = stream({ memberCosts: new Map(), transcript: [entry(A, 0)] });
    expect(buildDigest(conv, live, PHASES).totalCost).toBeCloseTo(0.4);
  });
});

describe("buildDigest — stances", () => {
  it("reads the persisted stance and marks an extracted one as a quote", () => {
    const conv = conversation({
      transcript: [entry(A, 0)],
      memberStances: {
        [A]: {
          text: "Favours pgvector.",
          coveredContributions: 1,
          llmGenerated: false,
          updated: "2026-09-22T10:01:00Z",
        },
      },
    });
    const member = buildDigest(conv, undefined, PHASES).members[0];
    expect(member?.stance).toBe("Favours pgvector.");
    expect(member?.stanceIsQuote).toBe(true);
  });

  it("marks an LLM stance as not a quote", () => {
    const live = stream({
      transcript: [entry(A, 0)],
      stances: new Map([
        [A, { agentId: A, displayName: A, stance: "Backs the migration.", llmGenerated: true, coveredContributions: 1 }],
      ]),
    });
    const member = buildDigest(null, live, PHASES).members[0];
    expect(member?.stance).toBe("Backs the migration.");
    expect(member?.stanceIsQuote).toBe(false);
  });

  it("prefers the live stance over the persisted one while streaming", () => {
    const conv = conversation({
      transcript: [entry(A, 0)],
      memberStances: {
        [A]: { text: "Old.", coveredContributions: 1, llmGenerated: false, updated: "2026-09-22T10:00:00Z" },
      },
    });
    const live = stream({
      transcript: [entry(A, 0)],
      stances: new Map([
        [A, { agentId: A, displayName: A, stance: "New.", llmGenerated: false, coveredContributions: 2 }],
      ]),
    });
    expect(buildDigest(conv, live, PHASES).members[0]?.stance).toBe("New.");
  });

  it("leaves the stance null for a member who has not spoken", () => {
    const digest = buildDigest(conversation(), undefined, PHASES, { [A]: "Architect" });
    expect(digest.members[0]?.stance).toBeNull();
  });

  it("extracts a stance locally when none was supplied", () => {
    // Every conversation written before this feature carries no stances, and a
    // live one has none until its first phase boundary. Without the fallback
    // the roster reads "has not spoken yet" beside a matrix showing their turns.
    const conv = conversation({
      transcript: [entry(A, 0, "OPINION", "We should adopt pgvector. It halves the ops surface.")],
    });
    const member = buildDigest(conv, undefined, PHASES).members[0];
    expect(member?.stance).toBe("We should adopt pgvector.");
    expect(member?.stanceIsQuote).toBe(true);
  });

  it("extracts from the newest stance-bearing entry", () => {
    const conv = conversation({
      transcript: [
        entry(A, 0, "OPINION", "First position."),
        entry(A, 0, "REVISION", "Revised position."),
      ],
    });
    expect(buildDigest(conv, undefined, PHASES).members[0]?.stance).toBe("Revised position.");
  });

  it("does not extract from an abstention or a failure", () => {
    // An abstention's content is a refusal to add anything; using it would
    // replace the member's real position with "I have nothing to add".
    const conv = conversation({
      transcript: [
        entry(A, 0, "OPINION", "The real position."),
        entry(A, 0, "ABSTAINED", "Nothing to add."),
        entry(A, 0, "ERROR", "timed out"),
      ],
    });
    expect(buildDigest(conv, undefined, PHASES).members[0]?.stance).toBe("The real position.");
  });

  it("does not mistake a decimal or an abbreviation for a sentence end", () => {
    const decimals = conversation({
      transcript: [entry(A, 0, "OPINION", "It costs $1.50 per seat. That is fine.")],
    });
    expect(buildDigest(decimals, undefined, PHASES).members[0]?.stance).toBe("It costs $1.50 per seat.");

    const abbrev = conversation({
      transcript: [entry(A, 0, "OPINION", "Use a managed store, e.g. pgvector. It is simpler.")],
    });
    expect(buildDigest(abbrev, undefined, PHASES).members[0]?.stance).toBe(
      "Use a managed store, e.g. pgvector.",
    );
  });

  it("caps an extracted stance so the roster stays one line", () => {
    const conv = conversation({
      transcript: [entry(A, 0, "OPINION", "word ".repeat(200))],
    });
    const stance = buildDigest(conv, undefined, PHASES).members[0]?.stance;
    expect(stance).toBeTruthy();
    expect(stance!.length).toBeLessThanOrEqual(160);
    expect(stance!.endsWith("…")).toBe(true);
  });

  it("prefers a supplied stance over the local extraction", () => {
    const conv = conversation({
      transcript: [entry(A, 0, "OPINION", "The raw sentence.")],
      memberStances: {
        [A]: { text: "A model's paraphrase.", coveredContributions: 1, llmGenerated: true, updated: "2026-09-22T10:01:00Z" },
      },
    });
    const member = buildDigest(conv, undefined, PHASES).members[0];
    expect(member?.stance).toBe("A model's paraphrase.");
    expect(member?.stanceIsQuote).toBe(false);
  });
});

describe("buildDigest — members", () => {
  it("orders by first contribution, then appends roster-only members", () => {
    const conv = conversation({ transcript: [entry(B, 0), entry(A, 0)] });
    const digest = buildDigest(conv, undefined, PHASES, { [A]: "Architect", [MOD]: "Moderator" });
    expect(digest.members.map((m) => m.agentId)).toEqual([B, A, MOD]);
  });

  it("marks the speaking member from the live stream", () => {
    const live = stream({ transcript: [entry(A, 0)], activeSpeakers: new Set([A]) });
    expect(buildDigest(null, live, PHASES).members[0]?.status).toBe("speaking");
  });

  it("does not count a failed turn toward the turn count", () => {
    const conv = conversation({ transcript: [entry(A, 0), entry(A, 0, "ERROR", "boom")] });
    const member = buildDigest(conv, undefined, PHASES).members[0];
    expect(member?.turnCount).toBe(1);
    expect(member?.hasFailed).toBe(true);
    expect(member?.status).toBe("failed");
  });

  it("flags a dissenting member", () => {
    const conv = conversation({ transcript: [entry(A, 0, "DISSENT", "No.")] });
    const member = buildDigest(conv, undefined, PHASES).members[0];
    expect(member?.hasDissented).toBe(true);
    expect(member?.status).toBe("dissented");
  });

  it("prefers the conversation's display names over the roster's", () => {
    const conv = conversation({
      transcript: [entry(A, 0)],
      memberDisplayNames: { [A]: "Renamed Architect" },
    });
    const digest = buildDigest(conv, undefined, PHASES, { [A]: "Stale Name" });
    expect(digest.members[0]?.displayName).toBe("Renamed Architect");
  });
});

describe("buildDigest — source precedence", () => {
  it("uses the persisted transcript once the stream has stopped", () => {
    // A reload mid-discussion leaves a short stream transcript behind; the
    // stored document is the authority afterwards.
    const conv = conversation({ transcript: [entry(A, 0), entry(B, 0), entry(MOD, 1)] });
    const stopped = stream({ isStreaming: false, transcript: [entry(A, 0)] });
    expect(buildDigest(conv, stopped, PHASES).totalEntries).toBe(3);
  });

  it("uses the stream's transcript while it is running", () => {
    const conv = conversation({ transcript: [entry(A, 0)] });
    const live = stream({ transcript: [entry(A, 0), entry(B, 0)] });
    expect(buildDigest(conv, live, PHASES).totalEntries).toBe(2);
  });

  it("reports empty when there is genuinely nothing", () => {
    expect(buildDigest(null, undefined, null).isEmpty).toBe(true);
    expect(buildDigest().isEmpty).toBe(true);
  });

  it("carries an end timestamp only once the stream has stopped", () => {
    const conv = conversation();
    expect(buildDigest(conv, stream(), PHASES).endedAt).toBeNull();
    expect(buildDigest(conv, undefined, PHASES).endedAt).toBe("2026-09-22T10:05:00.000Z");
  });
});
