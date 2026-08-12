import { describe, it, expect } from "vitest";
import { findGateCarryingCalls, uriTargetsLlmStore } from "../gate-guard";
import type { PendingToolCallView, ResolvedRequestPreview } from "@/lib/api/hitl";

function call(
  callId: string,
  preview: Partial<ResolvedRequestPreview> | null,
  requestPinned = true,
): PendingToolCallView {
  return {
    callId,
    toolName: "http_put_llmstore",
    source: "http",
    argsTruncated: false,
    requestPinned,
    requestPreview: preview
      ? {
          method: "PUT",
          uri: "https://eddi.example/llmstore/llms/abc123?version=3",
          queryParams: {},
          headers: {},
          body: null,
          bodyTruncated: false,
          ...preview,
        }
      : null,
  };
}

/** A realistic llm document, with the gate field parameterised. */
function llmBody(task: Record<string, unknown>): string {
  return JSON.stringify({
    packageExtensionType: "eddi://ai.labs.llm",
    configs: [{ id: "1", task: { type: "llm", parameters: { systemMessage: "Hi." }, ...task } }],
  });
}

describe("uriTargetsLlmStore", () => {
  it("matches the store path anywhere in the URI", () => {
    expect(uriTargetsLlmStore("https://eddi.example/llmstore/llms/abc?version=2")).toBe(true);
    expect(uriTargetsLlmStore("/llmstore/llms")).toBe(true);
  });

  it("matches case-insensitively and through percent-encoding", () => {
    // Same asymmetry `uriTargetsAgent` reasons from: a false positive costs one
    // refused approval, a false negative costs the gate.
    expect(uriTargetsLlmStore("/LLMSTORE/LLMS/abc")).toBe(true);
    expect(uriTargetsLlmStore("/%6Clmstore/llms/abc")).toBe(true);
  });

  it("does not match a different store, or a blank uri", () => {
    expect(uriTargetsLlmStore("/rulestore/rulesets/abc")).toBe(false);
    expect(uriTargetsLlmStore("/agentstore/agents/abc")).toBe(false);
    expect(uriTargetsLlmStore(null)).toBe(false);
    expect(uriTargetsLlmStore("")).toBe(false);
  });

  it("falls back to the raw string rather than allowing a malformed escape through", () => {
    expect(uriTargetsLlmStore("/llmstore/llms/%E0%A4%A")).toBe(true);
  });
});

describe("findGateCarryingCalls", () => {
  it("refuses a body carrying toolApprovals", () => {
    const found = findGateCarryingCalls([
      call("c1", { body: llmBody({ toolApprovals: { requireApproval: [] } }) }),
    ]);
    expect(found).toEqual([{ callId: "c1", reason: "carries-gate" }]);
  });

  it("refuses it at ANY depth, not only where the backend model puts it", () => {
    // The operator composes the body itself, so a guard that only looked at the
    // documented path would be defeated by a slightly different shape.
    const deep = JSON.stringify({ a: { b: [{ c: { toolApprovals: {} } }] } });
    expect(findGateCarryingCalls([call("c1", { body: deep })])).toEqual([
      { callId: "c1", reason: "carries-gate" },
    ]);
  });

  it("refuses even a toolApprovals copied back unchanged — the guard is on presence, not on change", () => {
    // Deliberate: proving "unchanged" needs the prior version, which this pure
    // body check does not have. Omitting the field falls back to the agent-level
    // gate, which is the safe resolution.
    const found = findGateCarryingCalls([
      call("c1", { body: llmBody({ toolApprovals: { requireApproval: ["http.post:*"] } }) }),
    ]);
    expect(found).toHaveLength(1);
  });

  it("allows an ordinary prompt/model edit that carries no gate", () => {
    const body = llmBody({ parameters: { systemMessage: "You are helpful.", model: "claude-sonnet-5" } });
    expect(findGateCarryingCalls([call("c1", { body })])).toEqual([]);
  });

  it("refuses an UNPINNED request — a best-effort preview can change before it runs", () => {
    // PendingToolCallView's own contract: an unpinned http call is previewed
    // best-effort and "can still change before it runs". A body inspected and
    // found gate-free can therefore execute carrying toolApprovals — a bypass of
    // this control, not a gap in it. Only a pinned request is re-checked against
    // its fingerprint immediately before execution.
    const unpinned = {
      callId: "c1",
      toolName: "http_put_llmstore",
      source: "http",
      argsTruncated: false,
      requestPinned: false,
      requestPreview: {
        method: "PUT",
        uri: "https://eddi.example/llmstore/llms/abc123",
        queryParams: {},
        headers: {},
        body: llmBody({}),
        bodyTruncated: false,
      },
    } as PendingToolCallView;
    expect(findGateCarryingCalls([unpinned])).toEqual([{ callId: "c1", reason: "unpinned-request" }]);
  });

  it("refuses a truncated body — not seeing the field is not the field being absent", () => {
    const found = findGateCarryingCalls([
      call("c1", { body: llmBody({}), bodyTruncated: true }),
    ]);
    expect(found).toEqual([{ callId: "c1", reason: "unverifiable-body" }]);
  });

  it("refuses an unparseable body rather than substring-guessing", () => {
    const found = findGateCarryingCalls([call("c1", { body: "{not json" })]);
    expect(found).toEqual([{ callId: "c1", reason: "unverifiable-body" }]);
  });

  it("ignores reads — introspecting an llm config is how it answers 'what prompt is this?'", () => {
    for (const method of ["GET", "HEAD", ""]) {
      const found = findGateCarryingCalls([
        call("c1", { method, body: llmBody({ toolApprovals: {} }) }),
      ]);
      expect(found, method || "(blank method)").toEqual([]);
    }
  });

  it("ignores writes to any other store, gate-shaped body or not", () => {
    const found = findGateCarryingCalls([
      call("c1", {
        uri: "https://eddi.example/rulestore/rulesets/abc",
        body: JSON.stringify({ toolApprovals: { requireApproval: [] } }),
      }),
    ]);
    expect(found).toEqual([]);
  });

  it("ignores a bodyless write — there is nothing that could carry a gate", () => {
    expect(findGateCarryingCalls([call("c1", { body: null })])).toEqual([]);
    expect(findGateCarryingCalls([call("c1", { body: "   " })])).toEqual([]);
  });

  it("refuses an UNPINNED write whatever its body shape — pinned is checked first", () => {
    // Regression. The pinned check used to sit BELOW the empty-body early
    // return, so an unpinned write previewed with no body fell through the guard
    // entirely and left Approve enabled. "We were shown no body" is not "no body
    // will be sent": on an unpinned call the preview is not what runs, so no body
    // shape can establish anything.
    //
    // Reachable by contract, not just in theory — PendingToolCallView documents
    // requestPinned as "independent of whether requestPreview itself is present:
    // a best-effort preview can exist while this is false", and
    // ResolvedRequestPreview.body is `?: string | null`.
    for (const body of [null, undefined, "", "   ", llmBody({}), "{not json"]) {
      const found = findGateCarryingCalls([call("c1", { body }, false)]);
      expect(found, `body=${JSON.stringify(body)}`).toEqual([
        { callId: "c1", reason: "unpinned-request" },
      ]);
    }
  });

  it("refuses an UNPINNED write ahead of the truncated-body reason", () => {
    // Both conditions hold; unpinned is the more fundamental one and must win,
    // so the approver is told the request can still change rather than merely
    // that it is too big to display.
    const found = findGateCarryingCalls([
      call("c1", { body: llmBody({}), bodyTruncated: true }, false),
    ]);
    expect(found).toEqual([{ callId: "c1", reason: "unpinned-request" }]);
  });

  it("ignores a call with no preview at all — it carries its own unpinned warning", () => {
    // Same line `findSelfTargetedCalls` draws: refusing every unpreviewable call
    // would block legitimate non-http tools entirely. Distinct from a TRUNCATED
    // body, which is previewed and pinned but cannot be read in full.
    expect(findGateCarryingCalls([call("c1", null)])).toEqual([]);
  });

  it("reports each offending call in a mixed batch, and only those", () => {
    const found = findGateCarryingCalls([
      call("ok", { body: llmBody({}) }),
      call("bad", { body: llmBody({ toolApprovals: {} }) }),
      call("truncated", { body: llmBody({}), bodyTruncated: true }),
      call("other-store", { uri: "/outputstore/outputsets/x", body: llmBody({ toolApprovals: {} }) }),
    ]);
    expect(found).toEqual([
      { callId: "bad", reason: "carries-gate" },
      { callId: "truncated", reason: "unverifiable-body" },
    ]);
  });

  it("returns nothing for an empty or absent batch", () => {
    expect(findGateCarryingCalls([])).toEqual([]);
    expect(findGateCarryingCalls(null)).toEqual([]);
    expect(findGateCarryingCalls(undefined)).toEqual([]);
  });
});
