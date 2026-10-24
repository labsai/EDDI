import { describe, expect, it } from "vitest";
import { ruleProblems } from "../alert-rules";
import { blankRule } from "../api/alerts";

describe("ruleProblems — mirrors AlertRuleValidator", () => {
  it("a named blank rule of either source is valid", () => {
    expect(ruleProblems({ ...blankRule("RATING", "agent1"), name: "x" })).toEqual([]);
    expect(ruleProblems({ ...blankRule("CONVERSATION_ERROR", ""), name: "x" })).toEqual([]);
  });

  it("needs a name, an agent and a criterion", () => {
    const rule = blankRule("RATING", "agent1");
    expect(ruleProblems(rule)).toContain("name");
    expect(ruleProblems({ ...rule, name: "x", scope: { allAgents: false, agentIds: [] } })).toContain("scope");
    expect(ruleProblems({ ...rule, name: "x", condition: { onComment: false } })).toContain("condition");
    expect(ruleProblems({ ...blankRule("CONVERSATION_ERROR", "a"), name: "x", condition: { errorKinds: [] } })).toContain("condition");
  });

  it("checks Slack channel ids and that a webhook secret is a vault reference", () => {
    const rule = { ...blankRule("RATING", "agent1"), name: "x" };
    expect(ruleProblems({ ...rule, delivery: [{ type: "SLACK", channelId: "#general" }] })).toEqual(["delivery0"]);
    expect(ruleProblems({ ...rule, delivery: [{ type: "WEBHOOK", url: "https://h.example.com", signingSecret: "plain" }] })).toEqual(["delivery0"]);
    expect(ruleProblems({ ...rule, delivery: [{ type: "WEBHOOK", url: "https://h.example.com", signingSecret: "${vault:k}" }] })).toEqual([]);
  });
});
