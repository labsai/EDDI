import { describe, it, expect } from "vitest";
import {
  CONVERGENCE_MIN_REPEATS_FLOOR,
  DEFAULT_CONVERGENCE_THRESHOLD,
  DEFAULT_GROUP_TASK_CONFIG,
  convergenceApplies,
  effectiveDelegationDepth,
  effectiveDelegationTimeout,
  hasDisplayableDecision,
  isValidCostCeiling,
  moderatorlessPhaseNames,
  normalizeConvergence,
  normalizeGroupTaskConfig,
} from "../group-config";
import type { AgentGroupConfiguration, DecisionRecord } from "../api/groups";

type ConfigSlice = Pick<
  AgentGroupConfiguration,
  "moderatorAgentId" | "phases" | "style" | "maxRounds"
>;

describe("moderatorlessPhaseNames", () => {
  it("is silent when a moderator is named", () => {
    const config: ConfigSlice = {
      moderatorAgentId: "mod-1", phases: null, style: "ROUND_TABLE", maxRounds: 2,
    };
    expect(moderatorlessPhaseNames(config)).toEqual([]);
  });

  it("treats a blank moderator id as no moderator", () => {
    const config: ConfigSlice = {
      moderatorAgentId: "   ", phases: null, style: "ROUND_TABLE", maxRounds: 2,
    };
    expect(moderatorlessPhaseNames(config)).toEqual(["Synthesis"]);
  });

  /**
   * The mistake the backend's own version calls out: checking only the STORED
   * phases makes this inert for exactly the configs that need it, because a
   * preset-style group stores none and every preset ends in a MODERATOR phase.
   */
  it("expands the preset when the group stores no phases", () => {
    expect(
      moderatorlessPhaseNames({ moderatorAgentId: null, phases: null, style: "PEER_REVIEW", maxRounds: 2 }),
    ).toEqual(["Synthesis"]);
    expect(
      moderatorlessPhaseNames({ moderatorAgentId: null, phases: null, style: "TASK_FORCE", maxRounds: 2 }),
    ).toEqual(["Task Planning", "Result Verification", "Final Synthesis"]);
  });

  it("uses the stored phases when the group has materialized them", () => {
    const config: ConfigSlice = {
      moderatorAgentId: null,
      style: "CUSTOM",
      maxRounds: 1,
      phases: [
        { name: "Open", type: "OPINION", participants: "ALL", turnOrder: "SEQUENTIAL", contextScope: "NONE", targetEachPeer: false, inputTemplate: null, repeats: 1 },
        { name: "Wrap", type: "SYNTHESIS", participants: "moderator", turnOrder: "SEQUENTIAL", contextScope: "FULL", targetEachPeer: false, inputTemplate: null, repeats: 1 },
      ],
    };
    // Case-insensitive, as the backend's equalsIgnoreCase is.
    expect(moderatorlessPhaseNames(config)).toEqual(["Wrap"]);
  });

  it("reports nothing for a CUSTOM group with no phases at all", () => {
    expect(
      moderatorlessPhaseNames({ moderatorAgentId: null, phases: [], style: "CUSTOM", maxRounds: 1 }),
    ).toEqual([]);
  });
});

describe("isValidCostCeiling", () => {
  it("accepts a positive amount and 'no limit'", () => {
    expect(isValidCostCeiling(0.5)).toBe(true);
    expect(isValidCostCeiling(null)).toBe(true);
    expect(isValidCostCeiling(undefined)).toBe(true);
  });

  it("rejects the values the backend silently turns into 'unlimited'", () => {
    // Saving 0 would mean the exact opposite of what it reads as.
    expect(isValidCostCeiling(0)).toBe(false);
    expect(isValidCostCeiling(-1)).toBe(false);
    expect(isValidCostCeiling(NaN)).toBe(false);
  });
});

describe("convergenceApplies", () => {
  it("needs a phase that repeats", () => {
    expect(convergenceApplies({ repeats: 3 })).toBe(true);
    expect(convergenceApplies({ repeats: 1 })).toBe(false);
    expect(convergenceApplies({ repeats: 0 })).toBe(false);
  });
});

describe("normalizeConvergence", () => {
  it("raises minRepeats to the floor — a first repeat has no predecessor", () => {
    expect(normalizeConvergence({ enabled: true, minRepeats: 1 }).minRepeats).toBe(
      CONVERGENCE_MIN_REPEATS_FLOOR,
    );
    expect(normalizeConvergence({ enabled: true }).minRepeats).toBe(CONVERGENCE_MIN_REPEATS_FLOOR);
    expect(normalizeConvergence({ enabled: true, minRepeats: 5 }).minRepeats).toBe(5);
  });

  it("falls back to the default threshold outside (0,1]", () => {
    expect(normalizeConvergence({ enabled: true, threshold: 0 }).threshold).toBe(DEFAULT_CONVERGENCE_THRESHOLD);
    expect(normalizeConvergence({ enabled: true, threshold: 1.5 }).threshold).toBe(DEFAULT_CONVERGENCE_THRESHOLD);
    expect(normalizeConvergence({ enabled: true, threshold: -0.2 }).threshold).toBe(DEFAULT_CONVERGENCE_THRESHOLD);
    // 1.0 is the top of the range and must survive.
    expect(normalizeConvergence({ enabled: true, threshold: 1 }).threshold).toBe(1);
  });

  it("defaults the judge to MODERATOR", () => {
    expect(normalizeConvergence({ enabled: true }).judge).toBe("MODERATOR");
    expect(normalizeConvergence({ enabled: true, judge: "SERVICE" }).judge).toBe("SERVICE");
  });
});

describe("normalizeGroupTaskConfig", () => {
  it("is off with both caps defaulted", () => {
    expect(normalizeGroupTaskConfig({})).toEqual(DEFAULT_GROUP_TASK_CONFIG);
  });

  it("reads a non-positive cap as 'use the default', never as 'unlimited'", () => {
    const out = normalizeGroupTaskConfig({
      allowAgentTaskCreation: true,
      maxAgentAddedTasksPerDiscussion: 0,
      maxPerTurn: -4,
    });
    expect(out.maxAgentAddedTasksPerDiscussion).toBe(20);
    expect(out.maxPerTurn).toBe(3);
  });

  it("keeps explicit positive caps", () => {
    const out = normalizeGroupTaskConfig({
      allowAgentTaskCreation: true, maxAgentAddedTasksPerDiscussion: 7, maxPerTurn: 2,
    });
    expect(out).toEqual({ allowAgentTaskCreation: true, maxAgentAddedTasksPerDiscussion: 7, maxPerTurn: 2 });
  });
});

describe("delegation defaults", () => {
  it("substitutes the backend default for an absent or non-positive value", () => {
    expect(effectiveDelegationDepth(undefined)).toBe(3);
    expect(effectiveDelegationDepth(0)).toBe(3);
    expect(effectiveDelegationDepth(5)).toBe(5);
    expect(effectiveDelegationTimeout(undefined)).toBe(60);
    expect(effectiveDelegationTimeout(-1)).toBe(60);
    expect(effectiveDelegationTimeout(300)).toBe(300);
  });
});

describe("hasDisplayableDecision", () => {
  const decision = (extra: Partial<DecisionRecord>): DecisionRecord => ({
    type: "NONE", outcome: null, winner: null, tally: null, dissents: [],
    method: null, decidedAtPhase: null, ...extra,
  });

  it("shows any structured decision", () => {
    expect(hasDisplayableDecision(decision({ type: "VERDICT" }))).toBe(true);
  });

  it("hides a plain prose conclusion", () => {
    expect(hasDisplayableDecision(decision({}))).toBe(false);
    expect(hasDisplayableDecision(null)).toBe(false);
    expect(hasDisplayableDecision(undefined)).toBe(false);
  });

  /** A NONE carrying `raw` is a parse FAILURE, not an absence — hiding it would
   *  turn a real failure into a blank space. */
  it("shows a NONE whose judgment could not be parsed", () => {
    expect(hasDisplayableDecision(decision({ raw: "the judge said..." }))).toBe(true);
    expect(hasDisplayableDecision(decision({ raw: "   " }))).toBe(false);
  });

  it("shows a NONE that still collected dissents", () => {
    expect(
      hasDisplayableDecision(
        decision({ dissents: [{ agentId: "a", displayName: "A", position: "no" }] }),
      ),
    ).toBe(true);
  });
});
