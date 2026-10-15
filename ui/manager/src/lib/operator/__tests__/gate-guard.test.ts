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

describe("findGateCarryingCalls — PATCH (RFC 6902)", () => {
  /** A PATCH of an LLM document, body given as a list of operations. */
  const patchCall = (ops: unknown, overrides: Partial<ResolvedRequestPreview> = {}, pinned = true) =>
    call(
      "p1",
      {
        method: "PATCH",
        uri: "https://eddi.example/llmstore/llms/abc123?version=3",
        body: typeof ops === "string" ? ops : JSON.stringify(ops),
        ...overrides,
      },
      pinned,
    );
  const refused = (ops: unknown, reason: string) =>
    expect(findGateCarryingCalls([patchCall(ops)])).toEqual([{ callId: "p1", reason }]);
  const allowed = (ops: unknown) => expect(findGateCarryingCalls([patchCall(ops)])).toEqual([]);

  it("allows an ordinary edit — the credential-safe path the operator exists to use", () => {
    allowed([
      { op: "add", path: "/tasks/0/knowledgeBases", value: [{ name: "eddi-docs" }] },
      { op: "replace", path: "/tasks/0/parameters/modelName", value: "claude-sonnet-4-5" },
      { op: "remove", path: "/tasks/0/parameters/temperature" },
      { op: "test", path: "/tasks/0/type", value: "anthropic" },
    ]);
  });

  it("allows an empty patch", () => {
    allowed([]);
  });

  it("refuses an operation whose path is the gate field, or anything beneath it", () => {
    refused([{ op: "add", path: "/tasks/0/toolApprovals", value: { requireApproval: [] } }], "carries-gate");
    refused([{ op: "replace", path: "/tasks/0/toolApprovals/requireApproval", value: [] }], "carries-gate");
    refused([{ op: "add", path: "/tasks/0/toolApprovals/requireApproval/-", value: "x" }], "carries-gate");
  });

  it("refuses remove and test of the gate field too — the guard is on the field being in play", () => {
    refused([{ op: "remove", path: "/tasks/0/toolApprovals" }], "carries-gate");
    refused([{ op: "test", path: "/tasks/0/toolApprovals", value: null }], "carries-gate");
  });

  it("refuses a path that spells the field differently in case", () => {
    refused([{ op: "add", path: "/tasks/0/TOOLAPPROVALS", value: {} }], "carries-gate");
  });

  it("refuses a malformed pointer that merely contains the field name — fail closed", () => {
    refused([{ op: "add", path: "tasks/0/toolApprovals", value: {} }], "carries-gate");
  });

  it("refuses a value carrying the gate at any depth, wherever it is put", () => {
    refused([{ op: "add", path: "/tasks/-", value: { type: "anthropic", toolApprovals: {} } }], "carries-gate");
    refused([{ op: "replace", path: "/tasks/0", value: { type: "x", nested: [{ toolApprovals: {} }] } }], "carries-gate");
  });

  it("refuses replacing the whole /tasks array or the root with a value that carries it", () => {
    refused([{ op: "replace", path: "/tasks", value: [{ toolApprovals: {} }] }], "carries-gate");
    refused([{ op: "replace", path: "", value: { tasks: [{ toolApprovals: {} }] } }], "carries-gate");
  });

  it("allows replacing a whole task by one with no override — it falls back to the agent-level gate", () => {
    // The residual gate-guard.ts states for a PUT, unchanged: narrowing one task's
    // own protection, never an ungated agent.
    allowed([{ op: "replace", path: "/tasks/0", value: { type: "anthropic", parameters: { modelName: "m" } } }]);
  });

  it("refuses move and copy, from anywhere — they can carry a stored override or reference somewhere else", () => {
    refused([{ op: "copy", from: "/tasks/0", path: "/tasks/1" }], "unsupported-patch-op");
    refused([{ op: "move", from: "/tasks/0/parameters", path: "/tasks/1/parameters" }], "unsupported-patch-op");
    refused([{ op: "copy", from: "/tasks/0/parameters/apiKey", path: "/tasks/0/parameters/baseUrl" }], "unsupported-patch-op");
  });

  it("refuses move and copy that name the gate as the more serious finding", () => {
    refused([{ op: "copy", from: "/tasks/0/toolApprovals", path: "/tasks/1/toolApprovals" }], "carries-gate");
    refused([{ op: "move", from: "/tasks/0/toolApprovals", path: "/tasks/1/parameters/x" }], "carries-gate");
    refused([{ op: "move", from: "/tasks/0/parameters/x", path: "/tasks/1/toolApprovals" }], "carries-gate");
  });

  it("refuses an operation it does not know", () => {
    refused([{ op: "frobnicate", path: "/tasks/0" }], "unsupported-patch-op");
    refused([{ op: "ADD", path: "/tasks/0/x", value: 1 }], "unsupported-patch-op");
  });

  it("one bad operation refuses the whole patch, however many good ones surround it", () => {
    refused(
      [
        { op: "replace", path: "/tasks/0/parameters/modelName", value: "m" },
        { op: "add", path: "/tasks/0/toolApprovals", value: {} },
        { op: "remove", path: "/tasks/0/parameters/temperature" },
      ],
      "carries-gate",
    );
  });

  it("fails closed on a body that is not a well-formed patch", () => {
    refused({ op: "add", path: "/x", value: 1 }, "unverifiable-body"); // an object, not a list
    refused([{ op: "add", value: 1 }], "unverifiable-body"); // no path
    refused([{ path: "/x" }], "unverifiable-body"); // no op
    refused([{ op: "add", path: 5 }], "unverifiable-body"); // path not a string
    refused([{ op: "move", from: 5, path: "/x" }], "unverifiable-body"); // from not a string
    refused(["add /x"], "unverifiable-body");
    refused("[{\"op\":\"add\"", "unverifiable-body"); // not JSON
  });

  it("fails closed on a truncated body, and on an unpinned request", () => {
    expect(
      findGateCarryingCalls([patchCall([{ op: "add", path: "/tasks/0/x", value: 1 }], { bodyTruncated: true })]),
    ).toEqual([{ callId: "p1", reason: "unverifiable-body" }]);
    expect(findGateCarryingCalls([patchCall([{ op: "add", path: "/tasks/0/x", value: 1 }], {}, false)])).toEqual([
      { callId: "p1", reason: "unpinned-request" },
    ]);
  });

  it("does not look at a PATCH to another store", () => {
    // Only llmstore documents can carry a gate; an apicall patch with the word in a
    // value is somebody's header, not an approval gate.
    expect(
      findGateCarryingCalls([
        patchCall([{ op: "add", path: "/httpCalls/0/toolApprovals", value: 1 }], {
          uri: "https://eddi.example/apicallstore/apicalls/abc123?version=3",
        }),
      ]),
    ).toEqual([]);
  });

  it("a PUT is still judged on its whole body, exactly as before", () => {
    expect(findGateCarryingCalls([call("c1", { body: llmBody({ toolApprovals: {} }) })])).toEqual([
      { callId: "c1", reason: "carries-gate" },
    ]);
  });
});
