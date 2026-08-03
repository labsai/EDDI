import { describe, it, expect } from "vitest";
import { detectEscalationFlags } from "../escalation-flags";

/** A minimal group config body, with dynamic agents off. */
function groupBody(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    name: "Billing review board",
    members: [{ agentId: "a1" }, { agentId: "a2" }],
    maxRounds: 2,
    ...overrides,
  });
}

describe("detectEscalationFlags", () => {
  it("finds nothing in an ordinary group create", () => {
    expect(detectEscalationFlags(groupBody())).toEqual([]);
  });

  it("flags a group that may create agents at runtime", () => {
    // The one this exists for: an approved group create that can go on to
    // create agents is an escape from the endpoint allow-list, and it is one
    // boolean deep in a config document nobody reads to the bottom of.
    const flags = detectEscalationFlags(
      groupBody({ dynamicAgents: { enabled: true, allowCreation: true } }),
    );
    expect(flags).toEqual([{ id: "dynamicAgentCreation", path: "dynamicAgents.allowCreation" }]);
  });

  it("flags a group that may recruit other agents", () => {
    const flags = detectEscalationFlags(
      groupBody({ dynamicAgents: { enabled: true, allowRecruitment: true } }),
    );
    expect(flags).toEqual([
      { id: "dynamicAgentRecruitment", path: "dynamicAgents.allowRecruitment" },
    ]);
  });

  it("reports both permissions when both are set", () => {
    const flags = detectEscalationFlags(
      groupBody({ dynamicAgents: { enabled: true, allowCreation: true, allowRecruitment: true } }),
    );
    expect(flags.map((f) => f.id)).toEqual(["dynamicAgentCreation", "dynamicAgentRecruitment"]);
  });

  it("does not cry wolf when the feature is switched off", () => {
    // The permission booleans carry non-false defaults in the backend model, so
    // flagging one while `enabled` is false would fire on ordinary groups and
    // train approvers to skim past the warning.
    expect(
      detectEscalationFlags(groupBody({ dynamicAgents: { enabled: false, allowCreation: true } })),
    ).toEqual([]);
  });

  it("flags a config that approves its own requests on timeout", () => {
    expect(
      detectEscalationFlags(groupBody({ hitlConfig: { timeoutPolicy: "AUTO_APPROVE" } })),
    ).toEqual([{ id: "autoApproveOnTimeout", path: "hitlConfig.timeoutPolicy" }]);
  });

  it("leaves a non-auto-approve timeout policy alone", () => {
    expect(
      detectEscalationFlags(groupBody({ hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY" } })),
    ).toEqual([]);
  });

  it("returns nothing for an absent or empty body", () => {
    expect(detectEscalationFlags(null)).toEqual([]);
    expect(detectEscalationFlags(undefined)).toEqual([]);
    expect(detectEscalationFlags("")).toEqual([]);
  });

  it("returns nothing for a body that is not JSON, rather than throwing", () => {
    // A form post or plain-text body is ordinary, not something to warn about.
    expect(detectEscalationFlags("name=x&value=y")).toEqual([]);
  });

  it("returns nothing for JSON that is not an object", () => {
    expect(detectEscalationFlags("[1,2,3]")).toEqual([]);
    expect(detectEscalationFlags('"a string"')).toEqual([]);
    expect(detectEscalationFlags("null")).toEqual([]);
  });

  it("tolerates a wrongly-typed nested value instead of throwing", () => {
    // The body is model output; nothing guarantees its shape.
    expect(detectEscalationFlags(groupBody({ dynamicAgents: "yes" }))).toEqual([]);
    expect(detectEscalationFlags(groupBody({ dynamicAgents: null }))).toEqual([]);
  });

  it("requires a real boolean, not a truthy string", () => {
    // A permissive `!!value` check would flag the string "false".
    expect(
      detectEscalationFlags(
        groupBody({ dynamicAgents: { enabled: "true", allowCreation: "false" } }),
      ),
    ).toEqual([]);
  });
});
