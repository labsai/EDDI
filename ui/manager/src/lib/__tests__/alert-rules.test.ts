import { describe, expect, it } from "vitest";
import { opsFor, outputMatchProblems, pathProblem, ruleProblems, throttleProblems } from "../alert-rules";
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
    expect(ruleProblems({ ...rule, delivery: [{ type: "WEBHOOK", url: "http://h.example.com", signingSecret: "${vault:k}" }] })).toEqual(["delivery0"]);
    expect(ruleProblems({ ...rule, delivery: [{ type: "WEBHOOK", url: "${vault:hook-url}", signingSecret: "${vault:k}" }] })).toEqual([]);
  });

  it("accepts a masked URL only together with its targetId", () => {
    const rule = { ...blankRule("RATING", "agent1"), name: "x" };
    const masked = { type: "WEBHOOK" as const, url: "https://hooks.example.com/***", signingSecret: "${vault:k}" };
    expect(ruleProblems({ ...rule, delivery: [{ ...masked, targetId: "tA" }] })).toEqual([]);
    expect(ruleProblems({ ...rule, delivery: [masked] })).toEqual(["delivery0"]);
  });

  it("checks the throttle with the server's bounds: whole numbers, 1–1440 minutes, 1–1000 per window", () => {
    const rule = { ...blankRule("RATING", "agent1"), name: "x" };
    const withThrottle = (windowMinutes: number, maxPerWindow: number) => ruleProblems({ ...rule, throttle: { windowMinutes, maxPerWindow } });
    expect(withThrottle(1, 1)).toEqual([]);
    expect(withThrottle(1440, 1000)).toEqual([]);
    expect(withThrottle(0, 10)).toEqual(["throttle"]);
    expect(withThrottle(1441, 10)).toEqual(["throttle"]);
    expect(withThrottle(60, 0)).toEqual(["throttle"]);
    expect(withThrottle(60, 1001)).toEqual(["throttle"]);
    expect(withThrottle(1.5, 10)).toEqual(["throttle"]);
    expect(withThrottle(60, Number.NaN)).toEqual(["throttle"]);
    expect(ruleProblems({ ...rule, throttle: null })).toEqual([]);
    expect(throttleProblems({ windowMinutes: 0, maxPerWindow: 2000 })).toEqual(["windowMinutes", "maxPerWindow"]);
  });
});

describe("OUTPUT_MATCH conditions — mirror OutputMatchCondition", () => {
  it("accepts the three path sources and refuses wildcards, empty segments and depth over 8", () => {
    expect(pathProblem("properties.escalate")).toBeNull();
    expect(pathProblem("output.json.result.sentiment")).toBeNull();
    expect(pathProblem("actions")).toBeNull();
    expect(pathProblem("properties.*")).toBe("path");
    expect(pathProblem("output.json.a..b")).toBe("path");
    expect(pathProblem("output.json.a.b.c.d.e.f.g.h.i")).toBe("depth");
    expect(pathProblem("memory.current.input")).toBe("path");
  });

  it("offers only the operators that fit the value type, and only contains/exists for actions", () => {
    expect(opsFor("properties", "number")).toContain("gt");
    expect(opsFor("properties", "text")).not.toContain("gt");
    expect(opsFor("properties", "list")).toEqual(["in"]);
    expect(opsFor("actions", "text")).toEqual(["contains", "exists"]);
  });

  it("checks op/value compatibility and the in-list cap", () => {
    expect(outputMatchProblems({ path: "properties.escalate", op: "eq", value: true })).toEqual([]);
    expect(outputMatchProblems({ path: "properties.score", op: "gt", value: "high" })).toContain("op");
    expect(outputMatchProblems({ path: "properties.x", op: "exists" })).toEqual([]);
    expect(outputMatchProblems({ path: "properties.x", op: "in", value: Array.from({ length: 51 }, (_, i) => `v${i}`) })).toContain("value");
    expect(outputMatchProblems({ path: "properties.x", op: "eq", value: "" })).toContain("value");
  });

  it("a rule with a bad output-match condition is refused", () => {
    const rule = { ...blankRule("OUTPUT_MATCH", "agent1"), name: "x" };
    expect(ruleProblems(rule)).toEqual([]);
    expect(ruleProblems({ ...rule, condition: { path: "properties.*", op: "eq", value: 1 } })).toContain("condition");
  });
});
