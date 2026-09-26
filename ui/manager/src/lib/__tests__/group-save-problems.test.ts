import { describe, it, expect } from "vitest";
import {
  DEFAULT_GROUP_TASK_CONFIG,
  groupSaveProblems,
  groupSaveProblemMessage,
  normalizeGroupTaskConfig,
} from "@/lib/group-config";
import { getStylePhases } from "@/lib/hitl-config";
import type { TFunction } from "i18next";

const agent = (id: string, role: string | null = null) => ({
  agentId: id,
  displayName: id,
  role,
  memberType: "AGENT",
});
const human = (id: string, name = "Ann") => ({ agentId: id, displayName: name, memberType: "HUMAN" });

describe("normalizeGroupTaskConfig — fields it does not normalize survive", () => {
  it("keeps assignmentMode, so a BID group is not saved back as ROLE", () => {
    const out = normalizeGroupTaskConfig({ ...DEFAULT_GROUP_TASK_CONFIG, assignmentMode: "BID" });
    expect(out.assignmentMode).toBe("BID");
  });

  it("still defaults non-positive caps", () => {
    const out = normalizeGroupTaskConfig({ assignmentMode: "BID", maxPerTurn: 0 });
    expect(out.maxPerTurn).toBe(3);
    expect(out.assignmentMode).toBe("BID");
  });
});

describe("groupSaveProblems — mirrors AgentGroupStore's hard rejections", () => {
  it("is empty for a valid group", () => {
    expect(
      groupSaveProblems({ members: [agent("a"), agent("b")], style: "ROUND_TABLE", phases: null, maxRounds: 2 }),
    ).toEqual([]);
  });

  it("flags a debate without both sides", () => {
    expect(
      groupSaveProblems({ members: [agent("a", "PRO"), agent("b")], style: "DEBATE", phases: null }),
    ).toEqual([{ kind: "debateRoles" }]);
    // Trimmed and case-insensitive, like the backend.
    expect(
      groupSaveProblems({ members: [agent("a", " pro "), agent("b", "Con")], style: "DEBATE", phases: null }),
    ).toEqual([]);
  });

  it("flags a devil's advocate group without the role", () => {
    expect(groupSaveProblems({ members: [agent("a"), agent("b")], style: "DEVIL_ADVOCATE" })).toEqual([
      { kind: "devilAdvocateRole" },
    ]);
  });

  it("skips the preset role rule when the group stores explicit phases", () => {
    expect(
      groupSaveProblems({
        members: [agent("a"), agent("b")],
        style: "DEBATE",
        phases: getStylePhases("DEBATE", 1),
      }),
    ).toEqual([]);
  });

  it("counts members with no agent, except those the flow is about to create", () => {
    const members = [agent(""), agent(""), agent("c")];
    expect(groupSaveProblems({ members, style: "ROUND_TABLE" })).toEqual([
      { kind: "memberUnassigned", count: 2 },
    ]);
    expect(groupSaveProblems({ members, style: "ROUND_TABLE" }, (_m, i) => i === 0)).toEqual([
      { kind: "memberUnassigned", count: 1 },
    ]);
  });

  it("refuses a HUMAN member in a task force and in peer-targeted phases, preset-expanded", () => {
    expect(groupSaveProblems({ members: [agent("a"), human("u1")], style: "TASK_FORCE" })).toEqual([
      { kind: "humanInTaskForce" },
    ]);
    expect(groupSaveProblems({ members: [agent("a"), human("u1")], style: "PEER_REVIEW" })).toEqual([
      { kind: "humanWithPeerPhases" },
    ]);
    expect(groupSaveProblems({ members: [agent("a"), human("u1")], style: "ROUND_TABLE" })).toEqual([]);
  });

  it("needs a HUMAN member's name and principal id, and never counts it as unassigned", () => {
    expect(
      groupSaveProblems({ members: [agent("a"), human("", " ")], style: "ROUND_TABLE" }),
    ).toEqual([
      { kind: "humanNeedsName", count: 1 },
      { kind: "humanNeedsId", count: 1 },
    ]);
  });

  it("has a sentence for every problem", () => {
    const t = ((key: string, opts?: unknown) =>
      typeof opts === "string" ? opts : `${key}`) as unknown as TFunction;
    for (const kind of [
      "debateRoles",
      "devilAdvocateRole",
      "humanInTaskForce",
      "humanWithPeerPhases",
    ] as const) {
      expect(groupSaveProblemMessage(t, { kind })).toBeTruthy();
    }
    expect(groupSaveProblemMessage(t, { kind: "memberUnassigned", count: 2 })).toBeTruthy();
  });
});
