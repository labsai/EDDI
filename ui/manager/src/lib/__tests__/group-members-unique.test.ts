import { describe, it, expect } from "vitest";
import type { TFunction } from "i18next";
import {
  groupSaveProblemMessage,
  groupSaveProblems,
  idsTakenByOtherMembers,
  memberSeatKey,
  repeatedMemberIds,
} from "@/lib/group-config";

/**
 * EDDI 6.6 (`AgentGroupStore.duplicateMemberProblems`) refuses a group that
 * lists the same agent twice: a seat is keyed by the trimmed agentId whatever
 * the member type, and two seats would share one member conversation.
 */
// Interpolates the English default, as i18next does with no resources loaded.
const t = ((_key: string, opts: { defaultValue: string; ids: string }) =>
  opts.defaultValue.replace("{{ids}}", opts.ids)) as unknown as TFunction;

describe("one seat per agent", () => {
  it("keys a seat by the trimmed agentId, and a blank id by nothing", () => {
    expect(memberSeatKey({ agentId: " a1 " })).toBe("a1");
    expect(memberSeatKey({ agentId: "" })).toBeNull();
    expect(memberSeatKey({ agentId: null })).toBeNull();
    expect(memberSeatKey(null)).toBeNull();
  });

  it("finds repeated ids across member types and whitespace, once each", () => {
    expect(
      repeatedMemberIds([
        { agentId: "a1" },
        { agentId: "a2" },
        { agentId: " a1" },
        { agentId: "a1" },
        { agentId: "" },
        { agentId: "" },
      ]),
    ).toEqual(["a1"]);
    expect(repeatedMemberIds([{ agentId: "a1" }, { agentId: "a2" }])).toEqual([]);
  });

  it("the ids other members hold exclude the row's own and blanks", () => {
    const members = [{ agentId: "a1" }, { agentId: "a2" }, { agentId: "" }];
    expect([...idsTakenByOtherMembers(members, 0)]).toEqual(["a2"]);
    expect([...idsTakenByOtherMembers(members, 2)].sort()).toEqual(["a1", "a2"]);
  });

  it("groupSaveProblems reports a duplicate member — an AGENT and a GROUP with the same id included", () => {
    const problems = groupSaveProblems({
      members: [
        { agentId: "x", memberType: "AGENT" },
        { agentId: "x", memberType: "GROUP" },
      ],
      style: "ROUND_TABLE",
    });
    expect(problems).toContainEqual({ kind: "duplicateMember", ids: ["x"] });
    expect(groupSaveProblemMessage(t, problems.find((p) => p.kind === "duplicateMember")!)).toMatch(/x is listed more than once/);
  });

  it("raises nothing for distinct members", () => {
    expect(
      groupSaveProblems({ members: [{ agentId: "a" }, { agentId: "b" }], style: "ROUND_TABLE" }).some(
        (p) => p.kind === "duplicateMember",
      ),
    ).toBe(false);
  });
});
