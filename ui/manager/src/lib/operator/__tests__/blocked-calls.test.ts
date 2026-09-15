import { describe, it, expect } from "vitest";
import { findBlockedCalls } from "../blocked-calls";
import type { PendingToolCallView, ResolvedRequestPreview } from "@/lib/api/hitl";

/**
 * `self-guard` and `gate-guard` have their own tests. What was untested is the
 * thing the UI actually calls: the composition. A mutation run scored this file
 * 0% — every mutant survived, and two thirds of them were never executed at
 * all — while the three approval surfaces (operator chat, approvals inbox,
 * conversation detail) each depend on it agreeing with the other two about what
 * is refusable.
 *
 * So these tests are about the seam, not about re-deriving the guards: that
 * every refusal reaches the caller, that each one carries the reason written
 * for its own failure mode rather than a neighbour's, and that the callId is
 * the one `ApprovalBanner` will match against.
 */

const OPERATOR_ID = "68f1c2a9b34d5e6f70819a2b";
const CONVERSATION_ID = "68f1c0ffee0000000000beef";
const OTHER_ID = "aaaabbbbccccddddeeeeffff";

/**
 * A `t` that returns the key, so an assertion names the string it expects
 * instead of pinning English prose that translators are free to change.
 * Interpolation is applied, because `{{agentId}}` reaching the approver
 * unsubstituted is a real defect and one of these mutants hides there.
 */
function keyOnly(key: string, _defaultValue: string, options?: Record<string, unknown>): string {
  const agentId = options?.agentId;
  return agentId === undefined ? key : `${key}:${String(agentId)}`;
}

/** A `t` that returns the English fallback, for the interpolation check. */
function englishFallback(
  _key: string,
  defaultValue: string,
  options?: Record<string, unknown>,
): string {
  return defaultValue.replace(/\{\{(\w+)\}\}/g, (whole, name: string) =>
    options && name in options ? String(options[name]) : whole,
  );
}

function call(overrides: Partial<PendingToolCallView> = {}): PendingToolCallView {
  return {
    callId: "c1",
    toolName: "updateResourceInAgent",
    source: "http",
    arguments: "{}",
    argsTruncated: false,
    requestPinned: true,
    requestPreview: {
      method: "PUT",
      uri: `https://eddi.example/agentstore/agents/${OTHER_ID}/updateResourceUri?version=3`,
      queryParams: {},
      headers: {},
      body: null,
      bodyTruncated: false,
    },
    ...overrides,
  };
}

/** A write to the operator's own agent document — `target: "agent"`. */
function selfWrite(callId = "self-write"): PendingToolCallView {
  return call({
    callId,
    requestPreview: {
      method: "PUT",
      uri: `https://eddi.example/agentstore/agents/${OPERATOR_ID}/updateResourceUri?version=3`,
      queryParams: {},
      headers: {},
      body: null,
      bodyTruncated: false,
    },
  });
}

/** A test-drive of its own agent — `target: "self-start"`. */
function selfStart(callId = "self-start"): PendingToolCallView {
  return call({
    callId,
    toolName: "startConversation",
    requestPreview: {
      method: "POST",
      uri: `https://eddi.example/agents/${OPERATOR_ID}/start`,
      queryParams: {},
      headers: {},
      body: null,
      bodyTruncated: false,
    },
  });
}

/** A post into the conversation the operator is running in — `target: "conversation"`. */
function selfConversation(callId = "self-conversation"): PendingToolCallView {
  return call({
    callId,
    toolName: "say",
    requestPreview: {
      method: "POST",
      uri: `https://eddi.example/agents/${CONVERSATION_ID}`,
      queryParams: {},
      headers: {},
      body: '{"input":"hello"}',
      bodyTruncated: false,
    },
  });
}

/**
 * A write to the LLM store, whose body/pinning decides the gate reason.
 *
 * `requestPreview` is merged rather than replaced — spreading the overrides
 * wholesale would swap the partial in for the real preview and drop the URI,
 * at which point `uriTargetsLlmStore` misses and the test passes by refusing
 * nothing for the wrong reason.
 */
function llmWrite(
  callId: string,
  {
    requestPreview,
    ...overrides
  }: Omit<Partial<PendingToolCallView>, "requestPreview"> & {
    requestPreview?: Partial<ResolvedRequestPreview>;
  } = {},
): PendingToolCallView {
  return call({
    callId,
    toolName: "updateLlmConfiguration",
    ...overrides,
    requestPreview: {
      method: "PUT",
      uri: `https://eddi.example/llmstore/llms/${OTHER_ID}`,
      queryParams: {},
      headers: {},
      body: '{"configs":[{"task":{"model":"claude"}}]}',
      bodyTruncated: false,
      ...requestPreview,
    },
  });
}

describe("findBlockedCalls", () => {
  it("refuses nothing when there is nothing to refuse", () => {
    expect(findBlockedCalls([call()], OPERATOR_ID, CONVERSATION_ID, keyOnly)).toEqual([]);
  });

  it.each([
    ["null", null],
    ["undefined", undefined],
    ["empty", [] as PendingToolCallView[]],
  ])("returns [] for %s calls", (_label, calls) => {
    expect(findBlockedCalls(calls, OPERATOR_ID, CONVERSATION_ID, keyOnly)).toEqual([]);
  });

  it.each([
    ["a write to its own agent", selfWrite, "operator.approval.blockedSelfTarget", OPERATOR_ID],
    ["a test-drive of itself", selfStart, "operator.approval.blockedSelfStart", OPERATOR_ID],
    [
      "a post into its own conversation",
      selfConversation,
      "operator.approval.blockedSelfConversation",
      CONVERSATION_ID,
    ],
  ])("refuses %s with its own reason", (_label, make, key, agentId) => {
    const blocked = findBlockedCalls([make()], OPERATOR_ID, CONVERSATION_ID, keyOnly);
    expect(blocked).toEqual([{ callId: make().callId, reason: `${key}:${agentId}` }]);
  });

  it.each([
    ["a body carrying toolApprovals", { body: '{"configs":[{"task":{"toolApprovals":{}}}]}' }, "operator.approval.blockedGateCarrying"],
    ["a truncated body", { bodyTruncated: true }, "operator.approval.blockedGateUnverifiable"],
    ["an unparseable body", { body: "{not json" }, "operator.approval.blockedGateUnverifiable"],
  ])("refuses an LLM write with %s", (_label, preview, key) => {
    const target = llmWrite("llm-1", { requestPreview: preview });
    const blocked = findBlockedCalls([target], OPERATOR_ID, CONVERSATION_ID, keyOnly);
    expect(blocked).toEqual([{ callId: "llm-1", reason: key }]);
  });

  it("refuses an unpinned LLM write for being unpinned, not for its body", () => {
    const target = llmWrite("llm-2", { requestPinned: false });
    const blocked = findBlockedCalls([target], OPERATOR_ID, CONVERSATION_ID, keyOnly);
    expect(blocked).toEqual([{ callId: "llm-2", reason: "operator.approval.blockedGateUnpinned" }]);
  });

  it("gives every failure mode a non-empty English fallback", () => {
    // Deliberately not an assertion about the wording. The authoritative
    // English lives in en.json and copy is edited freely; a test pinning these
    // sentences would fail on every such edit and teach people to update the
    // expectation without reading it. What must hold is that each branch says
    // *something* — a blank reason renders an ApprovalBanner that refuses the
    // batch and does not say why, which is indistinguishable from a bug.
    const everyFailureMode = [
      selfWrite(),
      selfStart(),
      selfConversation(),
      llmWrite("g1", { requestPreview: { body: '{"toolApprovals":{}}' } }),
      llmWrite("g2", { requestPreview: { bodyTruncated: true } }),
      llmWrite("g3", { requestPinned: false }),
    ];

    const reasons = everyFailureMode.flatMap((c) =>
      findBlockedCalls([c], OPERATOR_ID, CONVERSATION_ID, englishFallback).map((b) => b.reason),
    );

    expect(reasons).toHaveLength(everyFailureMode.length);
    for (const reason of reasons) expect(reason.trim()).not.toBe("");
    expect(new Set(reasons).size).toBe(everyFailureMode.length);
  });

  it("interpolates the agent id into the reason the approver reads", () => {
    const [blocked] = findBlockedCalls([selfWrite()], OPERATOR_ID, CONVERSATION_ID, englishFallback);
    expect(blocked!.reason).toContain(OPERATOR_ID);
    expect(blocked!.reason).not.toContain("{{agentId}}");
  });

  it("reports both guards' hits, self-targeted first", () => {
    const blocked = findBlockedCalls(
      [llmWrite("gate", { requestPinned: false }), selfWrite("self")],
      OPERATOR_ID,
      CONVERSATION_ID,
      keyOnly,
    );

    expect(blocked.map((b) => b.callId)).toEqual(["self", "gate"]);
  });

  it("refuses every offending call in a batch, not just the first", () => {
    const blocked = findBlockedCalls(
      [selfWrite("s1"), call({ callId: "innocent" }), selfStart("s2")],
      OPERATOR_ID,
      CONVERSATION_ID,
      keyOnly,
    );

    expect(blocked.map((b) => b.callId)).toEqual(["s1", "s2"]);
  });

  it("still refuses the gate-carrying write when the acting ids are unknown", () => {
    // The approvals inbox renders pauses whose acting agent it has not resolved
    // yet. The self-guard cannot fire without an id; the gate guard does not
    // need one, and must not be silently disabled by the missing value.
    const blocked = findBlockedCalls(
      [llmWrite("gate", { requestPreview: { body: '{"toolApprovals":{}}' } }), selfWrite()],
      null,
      null,
      keyOnly,
    );

    expect(blocked).toEqual([{ callId: "gate", reason: "operator.approval.blockedGateCarrying" }]);
  });
});
