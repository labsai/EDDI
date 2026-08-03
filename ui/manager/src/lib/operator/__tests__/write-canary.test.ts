import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { runOperatorWriteCanary, enforceWriteCanaryGate, WRITE_CANARY_TARGET_ENDPOINT } from "../write-canary";
import type { OperatorConfig, FetchedSpec } from "@/lib/api/operator";

function config(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return {
    enabled: true,
    agentId: "op-1",
    version: 1,
    environment: "production",
    provider: "anthropic",
    model: "claude-sonnet-4-6",
    credentialKey: null,
    scope: "read_write",
    authMode: "caller-identity",
    promptBody: "Do the thing.",
    ...overrides,
  };
}

/** A spec whose only write operation is the descriptor patch this probe targets. */
function spec(): FetchedSpec {
  const paths = {
    "/descriptorstore/descriptors/{id}": { patch: { operationId: "patchDescriptor" } },
    "/agentstore/agents/descriptors": { get: { operationId: "getAgentDescriptors" } },
  };
  return { raw: { openapi: "3.1.0", paths }, paths };
}

function serveTurn(frames: string[]) {
  server.use(
    http.post("*/agents/:agentId/start", () =>
      HttpResponse.json({ location: "/agents/conv-1" }, { status: 201, headers: { Location: "/agents/conv-1" } }),
    ),
    http.post("*/agents/:conversationId/stream", () =>
      new HttpResponse(frames.join(""), { status: 200, headers: { "Content-Type": "text/event-stream" } }),
    ),
  );
}

const taskComplete = (trace: unknown) =>
  `event: task_complete\ndata: ${JSON.stringify({ taskId: "t", taskType: "ai.labs.llm", index: 0, toolTrace: trace })}\n\n`;

const doneWith = (conversationState: string) =>
  `event: done\ndata: ${JSON.stringify({ conversationState })}\n\n`;

describe("runOperatorWriteCanary", () => {
  let canaryReports: unknown[];

  beforeEach(() => {
    // A default success handler for the metrics relay, plus a capture of
    // every body it received — most tests only need the former; the two
    // relay-specific tests below use the capture directly.
    canaryReports = [];
    server.use(
      http.post("*/administration/operator/canary-result", async ({ request }) => {
        canaryReports.push(await request.json().catch(() => null));
        return new HttpResponse(null, { status: 204 });
      }),
      // The probe always ends its own conversation in a finally block, in
      // every test — give it somewhere to land.
      http.post("*/agents/:conversationId/endConversation", () => new HttpResponse(null, { status: 200 })),
    );
  });

  it("passes when the pause names exactly the expected descriptor-patch tool, and rejects it", async () => {
    serveTurn([
      taskComplete([{ type: "tool_call", tool: "patchDescriptor" }]),
      doneWith("AWAITING_HUMAN"),
    ]);
    let resumeBody: unknown;
    server.use(
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json({
          conversationId: "conv-1",
          state: "AWAITING_HUMAN",
          pauseDetails: {
            type: "TOOL_CALL",
            calls: [{ callId: "c1", toolName: "patchDescriptor", source: "http", argsTruncated: false }],
            executedUngatedCalls: [],
            outcomeUnknown: [],
          },
        }),
      ),
      http.post("*/agents/:conversationId/resume", async ({ request }) => {
        resumeBody = await request.json();
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("pass");
    expect(result.toolCalls).toBe(1);
    // Nothing this probe pauses may execute — the reject is not optional.
    expect(resumeBody).toMatchObject({ verdict: "REJECTED" });
  });

  it("fails when the write executes without ever pausing — the gate did not catch it", async () => {
    // The dangerous case this whole probe exists to detect: a tool_call for
    // the exact tool it provoked, followed by a normal READY completion.
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("fail");
    expect(result.error).toMatch(/executed without pausing/i);
  });

  it("is unknown, not fail, when the operator never attempted the write at all", async () => {
    // No agents to test against is a real, non-alarming outcome — it must not
    // be conflated with the gate having failed.
    serveTurn([
      taskComplete([{ type: "tool_call", tool: "getAgentDescriptors" }, { type: "tool_result", tool: "getAgentDescriptors", result: "[]" }]),
      doneWith("READY"),
    ]);

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(result.error).toMatch(/did not attempt/i);
  });

  it("is unknown when nothing paused and no tool was ever called", async () => {
    serveTurn(["event: token\ndata: I could not find any agents.\n\n", doneWith("READY")]);

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(result.toolCalls).toBe(0);
    expect(result.error).toMatch(/no agents on this platform/i);
  });

  it("is unknown — not pass — when the pause is real but not on the expected tool, and still rejects it", async () => {
    // Some OTHER gated call happened to pause (e.g. a rule-level pause, or a
    // different write). This probe cannot claim to have proven anything about
    // the descriptor-patch endpoint specifically.
    serveTurn([doneWith("AWAITING_HUMAN")]);
    let rejected = false;
    server.use(
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json({
          conversationId: "conv-1",
          state: "AWAITING_HUMAN",
          pauseDetails: {
            type: "TOOL_CALL",
            calls: [{ callId: "c1", toolName: "deployAgent", source: "http", argsTruncated: false }],
            executedUngatedCalls: [],
            outcomeUnknown: [],
          },
        }),
      ),
      http.post("*/agents/:conversationId/resume", () => {
        rejected = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(result.error).toMatch(/not on the expected/i);
    expect(rejected).toBe(true);
  });

  it("still rejects the pause even when reading its details fails", async () => {
    // An unread pause must never be left open on the platform just because a
    // GET failed — the reject (verdict alone) does not need pauseDetails.
    serveTurn([doneWith("AWAITING_HUMAN")]);
    let rejected = false;
    server.use(
      http.get("*/agents/:conversationId/approval-status", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      http.post("*/agents/:conversationId/resume", () => {
        rejected = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(rejected).toBe(true);
  });

  it("reports an in-band stream error as unknown, not fail", async () => {
    serveTurn(["event: error\ndata: model provider rejected the key\n\n"]);

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(result.error).toMatch(/model provider/i);
  });

  it("reports a transport failure as unknown, not fail, and does not throw", async () => {
    server.use(http.post("*/agents/:agentId/start", () => HttpResponse.json({ message: "boom" }, { status: 500 })));

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(result.error).toBeTruthy();
  });

  it("is unknown when no operator agent is configured, without making a network call", async () => {
    const result = await runOperatorWriteCanary(config({ agentId: null }), spec());
    expect(result.outcome).toBe("unknown");
    expect(result.error).toMatch(/no operator agent/i);
  });

  it("is unknown when the target endpoint cannot be resolved from the spec", () => {
    const emptySpec: FetchedSpec = { raw: {}, paths: {} };
    return runOperatorWriteCanary(config(), emptySpec).then((result) => {
      expect(result.outcome).toBe("unknown");
      expect(result.error).toMatch(/could not resolve/i);
    });
  });

  it("reports the outcome and duration to the metrics relay", async () => {
    serveTurn([
      taskComplete([{ type: "tool_call", tool: "patchDescriptor" }]),
      doneWith("AWAITING_HUMAN"),
    ]);
    server.use(
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json({
          conversationId: "conv-1",
          state: "AWAITING_HUMAN",
          pauseDetails: {
            type: "TOOL_CALL",
            calls: [{ callId: "c1", toolName: "patchDescriptor", source: "http", argsTruncated: false }],
            executedUngatedCalls: [],
            outcomeUnknown: [],
          },
        }),
      ),
    );

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("pass");
    expect(canaryReports).toHaveLength(1);
    expect(canaryReports[0]).toMatchObject({ outcome: "pass" });
    expect((canaryReports[0] as { durationMs: number }).durationMs).toBeGreaterThanOrEqual(0);
  });

  it("a failed relay report does not change the canary's own result", async () => {
    // The whole point of "best-effort": a broken metrics endpoint must not turn
    // a real security signal into a thrown error nobody sees.
    server.use(
      http.post("*/administration/operator/canary-result", () => HttpResponse.json({ message: "down" }, { status: 500 })),
    );
    serveTurn([
      taskComplete([{ type: "tool_call", tool: "patchDescriptor" }]),
      doneWith("READY"),
    ]);

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("fail");
    expect(result.error).toMatch(/executed without pausing/i);
  });

  it("resolves the exact endpoint WRITE_ENDPOINTS would need it to", () => {
    expect(WRITE_CANARY_TARGET_ENDPOINT).toBe("PATCH /descriptorstore/descriptors/{id}");
  });
});

describe("enforceWriteCanaryGate", () => {
  const VAR_URL = "*/variablestore/variables/default/platform.operator";

  beforeEach(() => {
    server.use(
      http.post("*/administration/operator/canary-result", () => new HttpResponse(null, { status: 204 })),
      http.post("*/agents/:conversationId/endConversation", () => new HttpResponse(null, { status: 200 })),
    );
  });

  it("is a no-op for read_only — no probe runs, nothing is deleted", async () => {
    let anyWriteCanaryRequestMade = false;
    server.use(
      http.post("*/agents/:agentId/start", () => {
        anyWriteCanaryRequestMade = true;
        return HttpResponse.json({ location: "/agents/conv-1" }, { status: 201 });
      }),
    );

    const result = await enforceWriteCanaryGate(config({ scope: "read_only" }), spec());

    expect(result).toBeNull();
    expect(anyWriteCanaryRequestMade).toBe(false);
  });

  it("returns the passing result and deletes nothing when the canary passes", async () => {
    serveTurn([
      taskComplete([{ type: "tool_call", tool: "patchDescriptor" }]),
      doneWith("AWAITING_HUMAN"),
    ]);
    let deleteCalled = false;
    server.use(
      http.get("*/agents/:conversationId/approval-status", () =>
        HttpResponse.json({
          conversationId: "conv-1",
          state: "AWAITING_HUMAN",
          pauseDetails: {
            type: "TOOL_CALL",
            calls: [{ callId: "c1", toolName: "patchDescriptor", source: "http", argsTruncated: false }],
            executedUngatedCalls: [],
            outcomeUnknown: [],
          },
        }),
      ),
      http.delete("*/agentstore/agents/:id", () => {
        deleteCalled = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const result = await enforceWriteCanaryGate(config(), spec());

    expect(result?.outcome).toBe("pass");
    expect(deleteCalled).toBe(false);
  });

  it("rolls the agent back and throws when the canary does not pass — the actual safety property", async () => {
    // The write executes without pausing — the gate is broken. This is the
    // scenario the whole rollback exists for: an agent that is ALREADY
    // deployed, right now, with a write tool that just proved unsafe.
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    let undeployed = false;
    let deleted = false;
    let configCleared = false;
    server.use(
      http.post("*/administration/:env/undeploy/:agentId", () => {
        undeployed = true;
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete("*/agentstore/agents/:id", ({ request }) => {
        deleted = true;
        // resetOperator's full-wipe semantics: cascade + permanent.
        expect(request.url).toContain("cascade=true");
        expect(request.url).toContain("permanent=true");
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete(VAR_URL, () => {
        configCleared = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    await expect(enforceWriteCanaryGate(config(), spec())).rejects.toThrow(/write canary did not pass/i);

    expect(undeployed).toBe(true);
    expect(deleted).toBe(true);
    expect(configCleared).toBe(true);
  });

  it("still rolls back when the canary is merely inconclusive (unknown), not just on a confirmed fail", async () => {
    // "Not proven safe" is the bar for rollback, not "proven unsafe" — an
    // agent this activation cannot vouch for must not stay live either way.
    serveTurn(["event: token\ndata: nothing useful\n\n", doneWith("READY")]);
    let deleted = false;
    server.use(http.delete("*/agentstore/agents/:id", () => { deleted = true; return new HttpResponse(null, { status: 200 }); }));

    await expect(enforceWriteCanaryGate(config(), spec())).rejects.toThrow(/write canary did not pass \(unknown\)/i);
    expect(deleted).toBe(true);
  });

  it("the thrown error names the outcome and carries the canary's own error detail", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    server.use(http.delete("*/agentstore/agents/:id", () => new HttpResponse(null, { status: 200 })));

    await expect(enforceWriteCanaryGate(config(), spec())).rejects.toThrow(/executed without pausing/i);
  });
});
