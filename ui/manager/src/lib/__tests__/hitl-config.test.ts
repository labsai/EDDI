import { describe, it, expect } from "vitest";
import {
  getStylePhases,
  applyApprovalPhases,
  isValidIsoDuration,
  requiresApprovalTimeout,
  DEFAULT_GROUP_HITL_CONFIG,
} from "@/lib/hitl-config";

describe("getStylePhases", () => {
  it("mirrors the TASK_FORCE preset exactly (plan → execute → verify → synthesis)", () => {
    const phases = getStylePhases("TASK_FORCE", 1);
    expect(phases.map((p) => [p.name, p.type])).toEqual([
      ["Task Planning", "PLAN"],
      ["Task Execution", "EXECUTE"],
      ["Result Verification", "VERIFY"],
      ["Final Synthesis", "SYNTHESIS"],
    ]);
    // Behavior-preserving: every preset phase uses inputTemplate=null and no approval by default.
    expect(phases.every((p) => p.inputTemplate === null)).toBe(true);
    expect(phases.every((p) => p.requiresApproval === false)).toBe(true);
    // Execution runs in parallel with task-only context.
    const exec = phases.find((p) => p.type === "EXECUTE")!;
    expect(exec.turnOrder).toBe("PARALLEL");
    expect(exec.contextScope).toBe("TASK_ONLY");
  });

  it("expands ROUND_TABLE by rounds", () => {
    expect(getStylePhases("ROUND_TABLE", 1).map((p) => p.name)).toEqual([
      "Initial Opinions",
      "Synthesis",
    ]);
    const three = getStylePhases("ROUND_TABLE", 3);
    expect(three.map((p) => p.name)).toEqual(["Initial Opinions", "Discussion", "Synthesis"]);
    expect(three.find((p) => p.name === "Discussion")!.repeats).toBe(2); // rounds - 1
  });

  it("expands DELPHI anonymous rounds", () => {
    expect(getStylePhases("DELPHI", 3).map((p) => p.name)).toEqual([
      "Round 1 (Independent)",
      "Round 2 (Anonymous)",
      "Round 3 (Anonymous)",
      "Synthesis",
    ]);
  });

  it("returns round-independent presets verbatim", () => {
    expect(getStylePhases("DEBATE", 5).map((p) => p.type)).toEqual([
      "ARGUE",
      "ARGUE",
      "REBUTTAL",
      "REBUTTAL",
      "SYNTHESIS",
    ]);
    expect(getStylePhases("PEER_REVIEW", 2).map((p) => p.name)).toEqual([
      "Initial Opinions",
      "Peer Critique",
      "Revision",
      "Synthesis",
    ]);
  });

  it("returns no phases for CUSTOM (user-defined)", () => {
    expect(getStylePhases("CUSTOM", 2)).toEqual([]);
  });
});

describe("applyApprovalPhases", () => {
  it("flags only the named phases", () => {
    const phases = getStylePhases("TASK_FORCE", 1);
    const flagged = applyApprovalPhases(phases, ["Task Execution"]);
    expect(flagged.filter((p) => p.requiresApproval).map((p) => p.name)).toEqual(["Task Execution"]);
    // Does not mutate the input.
    expect(phases.every((p) => p.requiresApproval === false)).toBe(true);
  });
});

describe("isValidIsoDuration", () => {
  it.each([
    ["PT15M", true],
    ["PT1H30M", true],
    ["PT30S", true],
    ["P1D", true], // backend Duration.parse accepts day components
    ["P1DT2H", true],
    ["PT0.5S", true], // fractional allowed on seconds
    // Duration.parse allows a fraction ONLY on seconds — these must be rejected
    // so we don't send a save-time 400.
    ["PT1.5H", false],
    ["PT2.5M", false],
    ["P1DT", false], // bare trailing "T"
    ["PT0S", false],
    ["15m", false],
    ["", false],
    ["P", false],
    ["PT", false],
    ["nonsense", false],
  ])("%s → %s", (input, expected) => {
    expect(isValidIsoDuration(input)).toBe(expected);
  });
});

describe("requiresApprovalTimeout", () => {
  it("is true only for finite policies", () => {
    expect(requiresApprovalTimeout("WAIT_INDEFINITELY")).toBe(false);
    expect(requiresApprovalTimeout(null)).toBe(false);
    expect(requiresApprovalTimeout(undefined)).toBe(false);
    expect(requiresApprovalTimeout("AUTO_APPROVE")).toBe(true);
    expect(requiresApprovalTimeout("AUTO_REJECT")).toBe(true);
    expect(requiresApprovalTimeout("ABORT")).toBe(true);
  });
});

describe("DEFAULT_GROUP_HITL_CONFIG", () => {
  it("defaults to wait-indefinitely / phase / fail", () => {
    expect(DEFAULT_GROUP_HITL_CONFIG).toEqual({
      approvalTimeout: null,
      timeoutPolicy: "WAIT_INDEFINITELY",
      granularity: "PHASE",
      onTaskRejection: "FAIL",
    });
  });
});
