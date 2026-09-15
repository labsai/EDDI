import { describe, it, expect } from "vitest";
import {
  validateToolPattern,
  validateToolApprovals,
  hasToolApprovalsErrors,
  toolApprovalsInheritsAutoApprove,
  levenshtein,
  KNOWN_TOOL_SOURCES,
} from "@/lib/hitl-tool-approvals";
import type { ToolApprovalsConfig } from "@/lib/api/hitl";

describe("validateToolPattern", () => {
  it("accepts a bare tool name", () => {
    expect(validateToolPattern("delete_account")).toBeNull();
  });

  it("accepts a lone wildcard", () => {
    expect(validateToolPattern("*")).toBeNull();
  });

  it("accepts every known source prefix", () => {
    for (const source of KNOWN_TOOL_SOURCES) {
      expect(validateToolPattern(`${source}:read_*`)).toBeNull();
    }
  });

  it("accepts a wildcard prefix (no known-source check)", () => {
    expect(validateToolPattern("*:transfer")).toBeNull();
  });

  it("rejects a blank pattern", () => {
    expect(validateToolPattern("")).toMatch(/must not be blank/);
    expect(validateToolPattern("   ")).toMatch(/must not be blank/);
  });

  it("rejects a pattern over 256 characters", () => {
    expect(validateToolPattern("a".repeat(257))).toMatch(/exceeds 256/);
    expect(validateToolPattern("a".repeat(256))).toBeNull();
  });

  it("rejects illegal characters (e.g. a space)", () => {
    expect(validateToolPattern("delete account")).toMatch(/illegal characters/);
    expect(validateToolPattern("send$")).toMatch(/illegal characters/);
  });

  it("rejects a leading or trailing colon", () => {
    expect(validateToolPattern(":mcp")).toMatch(/must not start or end with a colon/);
    expect(validateToolPattern("mcp:")).toMatch(/must not start or end with a colon/);
  });

  it("rejects an unknown source prefix and suggests a near match", () => {
    const err = validateToolPattern("mcpp:read_*");
    expect(err).toMatch(/unknown tool source prefix 'mcpp:'/);
    expect(err).toMatch(/did you mean 'mcp:'/);
  });

  it("rejects an unknown source prefix with no suggestion when far off", () => {
    const err = validateToolPattern("zzzzzz:read_*");
    expect(err).toMatch(/unknown tool source prefix/);
    expect(err).not.toMatch(/did you mean/);
  });
});

describe("levenshtein", () => {
  it("computes edit distance", () => {
    expect(levenshtein("mcp", "mcp")).toBe(0);
    expect(levenshtein("mcpp", "mcp")).toBe(1);
    expect(levenshtein("htpp", "http")).toBe(1);
    expect(levenshtein("kitten", "sitting")).toBe(3);
  });
});

describe("validateToolApprovals", () => {
  it("treats an empty config as valid (gate off)", () => {
    expect(validateToolApprovals({})).toEqual({});
    expect(validateToolApprovals({ requireApproval: [] })).toEqual({});
  });

  it("accepts a fully-populated valid config", () => {
    const cfg: ToolApprovalsConfig = {
      requireApproval: ["mcp:*", "delete_*"],
      exempt: ["mcp:read_*"],
      maxPausesPerTurn: 3,
      maxAutoApprovalsPerTurn: 2,
      onNoProgress: "WAIT_FOR_HUMAN",
      approvalTimeout: "PT30M",
      timeoutPolicy: "AUTO_REJECT",
      pauseReason: "Approval required for {toolNames}",
      pendingMessage: "Waiting for approval of {toolNames}…",
      inGroupTurns: "REJECT",
    };
    expect(validateToolApprovals(cfg)).toEqual({});
  });

  it("flags a bad requireApproval pattern with its index", () => {
    const errors = validateToolApprovals({ requireApproval: ["ok_*", "bad pattern"] });
    expect(errors.requireApproval).toMatch(/requireApproval\[1\]/);
    expect(errors.requireApproval).toMatch(/illegal characters/);
  });

  it("flags duplicate patterns within a list", () => {
    const errors = validateToolApprovals({ requireApproval: ["mcp:*", "mcp:*"] });
    expect(errors.requireApproval).toMatch(/duplicate pattern 'mcp:\*'/);
  });

  it("rejects exempt without any requireApproval", () => {
    const errors = validateToolApprovals({ exempt: ["mcp:read_*"] });
    expect(errors.exempt).toMatch(/no effect without requireApproval/);
  });

  it("rejects a pattern appearing in both requireApproval and exempt", () => {
    const errors = validateToolApprovals({
      requireApproval: ["mcp:*"],
      exempt: ["mcp:*"],
    });
    expect(errors.exempt).toMatch(/appears in both/);
  });

  it("enforces the maxPausesPerTurn range 1..10", () => {
    expect(validateToolApprovals({ maxPausesPerTurn: 0 }).maxPausesPerTurn).toMatch(/between 1 and 10/);
    expect(validateToolApprovals({ maxPausesPerTurn: 11 }).maxPausesPerTurn).toMatch(/between 1 and 10/);
    expect(validateToolApprovals({ maxPausesPerTurn: 1 }).maxPausesPerTurn).toBeUndefined();
    expect(validateToolApprovals({ maxPausesPerTurn: 10 }).maxPausesPerTurn).toBeUndefined();
  });

  it("enforces the maxAutoApprovalsPerTurn range 0..10", () => {
    expect(validateToolApprovals({ maxAutoApprovalsPerTurn: -1 }).maxAutoApprovalsPerTurn).toMatch(/between 0 and 10/);
    expect(validateToolApprovals({ maxAutoApprovalsPerTurn: 11 }).maxAutoApprovalsPerTurn).toMatch(/between 0 and 10/);
    expect(validateToolApprovals({ maxAutoApprovalsPerTurn: 0 }).maxAutoApprovalsPerTurn).toBeUndefined();
  });

  it("rejects an invalid onNoProgress value", () => {
    expect(validateToolApprovals({ onNoProgress: "NONSENSE" as never }).onNoProgress).toMatch(/must be one of/);
    expect(validateToolApprovals({ onNoProgress: "ABORT" }).onNoProgress).toBeUndefined();
  });

  it("rejects reserved inGroupTurns=INBOX and any non-REJECT value", () => {
    expect(validateToolApprovals({ inGroupTurns: "INBOX" as never }).inGroupTurns).toMatch(/reserved/);
    expect(validateToolApprovals({ inGroupTurns: "OTHER" as never }).inGroupTurns).toMatch(/must be REJECT/);
    expect(validateToolApprovals({ inGroupTurns: "REJECT" }).inGroupTurns).toBeUndefined();
  });

  it("requires a positive approvalTimeout for a finite tool-pause policy", () => {
    expect(validateToolApprovals({ timeoutPolicy: "AUTO_REJECT" }).approvalTimeout).toMatch(/requires an approvalTimeout/);
    expect(validateToolApprovals({ timeoutPolicy: "AUTO_REJECT", approvalTimeout: "30m" }).approvalTimeout).toMatch(/not a valid/);
    expect(validateToolApprovals({ timeoutPolicy: "AUTO_REJECT", approvalTimeout: "PT30M" }).approvalTimeout).toBeUndefined();
  });

  it("still rejects a malformed duration under WAIT_INDEFINITELY", () => {
    expect(validateToolApprovals({ timeoutPolicy: "WAIT_INDEFINITELY", approvalTimeout: "nope" }).approvalTimeout).toMatch(/not a valid/);
    expect(validateToolApprovals({ approvalTimeout: "PT5M" }).approvalTimeout).toBeUndefined();
  });

  it("caps pauseReason and pendingMessage at 500 characters", () => {
    expect(validateToolApprovals({ pauseReason: "x".repeat(501) }).pauseReason).toMatch(/maximum length of 500/);
    expect(validateToolApprovals({ pendingMessage: "x".repeat(501) }).pendingMessage).toMatch(/maximum length of 500/);
    expect(validateToolApprovals({ pauseReason: "x".repeat(500) }).pauseReason).toBeUndefined();
  });
});

describe("hasToolApprovalsErrors", () => {
  it("is false for a valid config and true when any field errs", () => {
    expect(hasToolApprovalsErrors(validateToolApprovals({}))).toBe(false);
    expect(hasToolApprovalsErrors(validateToolApprovals({ maxPausesPerTurn: 99 }))).toBe(true);
  });
});

describe("toolApprovalsInheritsAutoApprove", () => {
  it("is true when agent AUTO_APPROVE and toolApprovals has no own policy", () => {
    expect(toolApprovalsInheritsAutoApprove("AUTO_APPROVE", { requireApproval: ["mcp:*"] })).toBe(true);
  });

  it("is false when the toolApprovals block sets its own policy", () => {
    expect(
      toolApprovalsInheritsAutoApprove("AUTO_APPROVE", { requireApproval: ["mcp:*"], timeoutPolicy: "AUTO_REJECT" }),
    ).toBe(false);
  });

  it("is false when the agent policy is not AUTO_APPROVE, or no toolApprovals", () => {
    expect(toolApprovalsInheritsAutoApprove("WAIT_INDEFINITELY", { requireApproval: ["mcp:*"] })).toBe(false);
    expect(toolApprovalsInheritsAutoApprove("AUTO_APPROVE", null)).toBe(false);
    expect(toolApprovalsInheritsAutoApprove(undefined, undefined)).toBe(false);
  });
});
