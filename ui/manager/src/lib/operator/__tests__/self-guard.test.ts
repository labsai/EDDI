import { describe, it, expect } from "vitest";
import { findSelfTargetedCalls, uriTargetsAgent } from "../self-guard";
import type { PendingToolCallView } from "@/lib/api/hitl";

const OPERATOR_ID = "68f1c2a9b34d5e6f70819a2b";
const OTHER_ID = "aaaabbbbccccddddeeeeffff";

function call(overrides: Partial<PendingToolCallView> = {}): PendingToolCallView {
  return {
    callId: "c1",
    toolName: "updateResourceInAgent",
    source: "http",
    argumentsRedacted: "{}",
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
  } as PendingToolCallView;
}

/** A call whose preview points at the operator's own agent document. */
function selfTargeted(overrides: Partial<PendingToolCallView> = {}): PendingToolCallView {
  return call({
    requestPreview: {
      method: "PUT",
      uri: `https://eddi.example/agentstore/agents/${OPERATOR_ID}/updateResourceUri?version=3`,
      queryParams: {},
      headers: {},
      body: null,
      bodyTruncated: false,
    },
    ...overrides,
  });
}

describe("uriTargetsAgent", () => {
  it("matches the agent id anywhere in the URI", () => {
    expect(uriTargetsAgent(`https://x/agentstore/agents/${OPERATOR_ID}/updateResourceUri`, OPERATOR_ID)).toBe(true);
  });

  it("does not match a different agent", () => {
    expect(uriTargetsAgent(`https://x/agentstore/agents/${OTHER_ID}`, OPERATOR_ID)).toBe(false);
  });

  it("matches regardless of hex case — ObjectId parsing accepts A-F", () => {
    // A stored id is lowercase toHexString() output, but the driver's
    // parseHexString accepts A-F, so /agents/68F1C2A9… reaches the identical
    // document. A case-sensitive includes() waved that straight through.
    expect(uriTargetsAgent(`https://x/agentstore/agents/${OPERATOR_ID.toUpperCase()}`, OPERATOR_ID)).toBe(true);
    expect(uriTargetsAgent(`https://x/agentstore/agents/${OPERATOR_ID}`, OPERATOR_ID.toUpperCase())).toBe(true);
  });

  it("matches through percent-encoding", () => {
    // Same class of miss: encode any character in the path and the raw
    // substring test stops matching while the request reaches the same agent.
    expect(uriTargetsAgent(`https://x/agentstore%2Fagents%2F${OPERATOR_ID}`, OPERATOR_ID)).toBe(true);
  });

  it("still checks a URI with a malformed escape rather than failing open", () => {
    // decodeURIComponent throws on a stray '%'. Falling back to the raw string
    // keeps the guard live; returning false would allow the write.
    expect(uriTargetsAgent(`https://x/a%/agents/${OPERATOR_ID}`, OPERATOR_ID)).toBe(true);
  });

  it("never matches on a blank or absent id", () => {
    // The dangerous direction: "" is a substring of every string, so a missing
    // operator id must not silently refuse every write on the platform.
    expect(uriTargetsAgent("https://x/agentstore/agents/abc", "")).toBe(false);
    expect(uriTargetsAgent("https://x/agentstore/agents/abc", "   ")).toBe(false);
    expect(uriTargetsAgent("https://x/agentstore/agents/abc", null)).toBe(false);
    expect(uriTargetsAgent("https://x/agentstore/agents/abc", undefined)).toBe(false);
  });

  it("tolerates a missing URI", () => {
    expect(uriTargetsAgent(null, OPERATOR_ID)).toBe(false);
    expect(uriTargetsAgent(undefined, OPERATOR_ID)).toBe(false);
  });
});

describe("findSelfTargetedCalls", () => {
  it("refuses the write that repoints the operator's own agent", () => {
    // The hinge of the self-ungating chain — see the module doc.
    const hits = findSelfTargetedCalls([selfTargeted()], OPERATOR_ID);
    expect(hits).toEqual([{ callId: "c1", agentId: OPERATOR_ID }]);
  });

  it("leaves a write to any OTHER agent alone", () => {
    // The whole point of the feature is editing other agents; over-blocking
    // here would make the capability useless.
    expect(findSelfTargetedCalls([call()], OPERATOR_ID)).toEqual([]);
  });

  it("allows READING its own configuration", () => {
    // "What am I running?" is exactly how an operator should answer questions
    // about itself, and a GET cannot repoint anything.
    const read = selfTargeted({
      requestPreview: {
        method: "GET",
        uri: `https://eddi.example/agentstore/agents/${OPERATOR_ID}?version=3`,
        queryParams: {},
        headers: {},
        body: null,
        bodyTruncated: false,
      },
    } as Partial<PendingToolCallView>);
    expect(findSelfTargetedCalls([read], OPERATOR_ID)).toEqual([]);
  });

  it("catches a self-targeted write under any write verb, not just PUT", () => {
    for (const method of ["PUT", "POST", "PATCH", "DELETE"]) {
      const hit = selfTargeted({
        requestPreview: {
          method,
          uri: `https://eddi.example/agentstore/agents/${OPERATOR_ID}`,
          queryParams: {},
          headers: {},
          body: null,
          bodyTruncated: false,
        },
      } as Partial<PendingToolCallView>);
      expect(findSelfTargetedCalls([hit], OPERATOR_ID), method).toHaveLength(1);
    }
  });

  it("picks only the offending call out of a mixed batch", () => {
    const hits = findSelfTargetedCalls([call({ callId: "ok" }), selfTargeted({ callId: "bad" })], OPERATOR_ID);
    expect(hits.map((h) => h.callId)).toEqual(["bad"]);
  });

  it("blocks nothing when no operator agent id is known", () => {
    expect(findSelfTargetedCalls([selfTargeted()], undefined)).toEqual([]);
    expect(findSelfTargetedCalls([selfTargeted()], "")).toEqual([]);
  });

  it("ignores a call with no resolved preview rather than refusing it", () => {
    // An unpreviewable call is also unpinned and already carries its own
    // warning; refusing every one of them here would block non-http tools.
    expect(findSelfTargetedCalls([call({ requestPreview: null })], OPERATOR_ID)).toEqual([]);
  });

  it("tolerates absent input", () => {
    expect(findSelfTargetedCalls(null, OPERATOR_ID)).toEqual([]);
    expect(findSelfTargetedCalls(undefined, OPERATOR_ID)).toEqual([]);
    expect(findSelfTargetedCalls([], OPERATOR_ID)).toEqual([]);
  });
});
