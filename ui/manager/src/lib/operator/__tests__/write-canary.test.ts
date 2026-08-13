import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  runOperatorWriteCanary,
  enforceWriteCanaryGate,
  buildWriteCanaryPrompt,
  WRITE_CANARY_TARGET_ENDPOINT,
} from "../write-canary";
import { WRITE_ENDPOINTS } from "../tool-scopes";
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

  it("probes an endpoint the operator was actually granted", () => {
    // Pinning the literal above is not enough on its own: if this entry were
    // dropped from WRITE_ENDPOINTS, the operator would hold no such tool, the
    // probe could never provoke it, and EVERY read_write activation would report
    // "unknown" and roll itself back — a total outage of the feature, caused by
    // an edit in a different file that no test connected to this one.
    expect(WRITE_ENDPOINTS).toContain(WRITE_CANARY_TARGET_ENDPOINT);
  });
});

/**
 * The prompt is the whole reason the probe used to fail against a healthy
 * operator: asked to pick "any ONE" agent and rename it, with no stated reason,
 * Claude Sonnet 5 reproducibly listed the agents and then asked which one —
 * correct behaviour for an agent hardened against loosely-specified
 * instructions, and fatal for a probe that reads silence as "unproven".
 */
describe("buildWriteCanaryPrompt", () => {
  it("names the resolved tool, so the model does not have to guess which one to call", () => {
    expect(buildWriteCanaryPrompt("patchDescriptor", "op-1")).toContain("patchDescriptor");
  });

  it("removes the reasons the model stopped: ambiguity, asking first, and a spare listing step", () => {
    const prompt = buildWriteCanaryPrompt("patchDescriptor", "op-1");

    // Self-targeting: the probe names the operator's OWN descriptor id, so there
    // is no "pick one" ambiguity and no listing round-trip to stall on — and the
    // one catastrophic path (gate broken, write executes) marks an agent the
    // rollback deletes anyway instead of a production agent.
    expect(prompt).toContain("op-1");
    expect(prompt).toMatch(/your own agent/i);
    expect(prompt).not.toMatch(/any ONE/i);
    expect(prompt).not.toMatch(/FIRST agent/);
    expect(prompt).toMatch(/without asking me anything first/i);
    // And it must say the pause is expected, or a careful model reads an
    // interception as an error worth reporting back instead of a success.
    expect(prompt).toMatch(/intercepted for human approval/i);
  });

  it("still asks for the exact marker the failure message tells admins to search for", () => {
    // If these drift apart, the "search your agents for that text" advice on the
    // destructive path points at a string that was never written.
    expect(buildWriteCanaryPrompt("patchDescriptor", "op-1")).toContain(" [operator-write-canary]");
  });
});

describe("enforceWriteCanaryGate", () => {
  const VAR_URL = "*/variablestore/variables/default/platform.operator";

  /** Default: the deterministic check verifies the stored policy. Individual
   *  tests override this to drive the not-gated / old-backend / error paths. */
  const dryRunGated = () =>
    http.post("*/administration/operator/gate-dry-run", () =>
      HttpResponse.json({ policyPresent: true, gated: true, matchedPattern: "http.patch:*" }),
    );

  beforeEach(() => {
    server.use(
      dryRunGated(),
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

    await expect(enforceWriteCanaryGate(config(), spec())).rejects.toThrow(/did NOT hold/);

    expect(undeployed).toBe(true);
    expect(deleted).toBe(true);
    expect(configCleared).toBe(true);
  });

  /**
   * THE core semantic change of the dry-run integration: an operator whose
   * stored policy verified deterministically is not deleted just because the
   * model declined to attempt the probe's write. That deletion was the original
   * defect — activation as a coin flip on an LLM's tool choice.
   */
  it("proceeds — does NOT roll back — when the policy verified and the probe was merely inconclusive", async () => {
    serveTurn(["event: token\ndata: nothing useful\n\n", doneWith("READY")]);
    let deleted = false;
    server.use(http.delete("*/agentstore/agents/:id", () => { deleted = true; return new HttpResponse(null, { status: 200 }); }));

    const result = await enforceWriteCanaryGate(config(), spec());

    expect(deleted).toBe(false);
    expect(result?.outcome).toBe("unknown");
    // Honest, not upgraded: the caller sees exactly what was and wasn't proven.
    expect(result?.error).toMatch(/verified deterministically/i);
    expect(result?.error).toMatch(/probe was inconclusive/i);
  });

  /**
   * Deterministically broken configuration: the probe is NOT run — provoking a
   * write against a policy known not to gate it would execute it for real.
   */
  it("rolls back without running the probe when the dry-run says the write is not gated", async () => {
    server.use(
      http.post("*/administration/operator/gate-dry-run", () =>
        HttpResponse.json({ policyPresent: true, gated: false, matchedPattern: null }),
      ),
      http.delete("*/agentstore/agents/:id", () => new HttpResponse(null, { status: 200 })),
    );
    let probeStarted = false;
    server.use(
      http.post("*/agents/:agentId/start", () => {
        probeStarted = true;
        return HttpResponse.json({ location: "/agents/conv-1" }, { status: 201 });
      }),
    );

    const error = String(await enforceWriteCanaryGate(config(), spec()).catch((e: unknown) => e));

    expect(probeStarted).toBe(false);
    expect(error).toMatch(/did NOT hold/);
    expect(error).toMatch(/no probe was run and nothing was written/i);
    // Pins the RollbackFailure re-throw guard: without it the rollback's own
    // throw is caught again and re-wrapped, so the admin reads a generic
    // "could not verify / deterministic check failed" headline instead of the
    // proven-broken-gate one (and the operator is rolled back twice).
    expect(error).not.toMatch(/deterministic check failed/i);
    expect(error).not.toMatch(/could not verify/i);
  });

  /**
   * A backend that predates gate-dry-run (404) restores the old semantics
   * wholesale: with nothing verified, "unknown" must keep rolling back — "not
   * proven safe" stays the bar when there is no other evidence.
   */
  it("still rolls back an inconclusive probe against an old backend without gate-dry-run", async () => {
    server.use(http.post("*/administration/operator/gate-dry-run", () => new HttpResponse(null, { status: 404 })));
    serveTurn(["event: token\ndata: nothing useful\n\n", doneWith("READY")]);
    let deleted = false;
    server.use(http.delete("*/agentstore/agents/:id", () => { deleted = true; return new HttpResponse(null, { status: 200 }); }));

    const error = String(await enforceWriteCanaryGate(config(), spec()).catch((e: unknown) => e));

    expect(deleted).toBe(true);
    expect(error).toMatch(/not evidence that it is broken/i);
    expect(error).not.toMatch(/did NOT hold/);
    // An admin left with no operator needs a way forward, not just a verdict.
    expect(error).toMatch(/try activating again/i);
    expect(error).toMatch(/read-only/i);
  });

  it("fails closed when the dry-run itself errors (not 404) — verification failure, not breach", async () => {
    server.use(
      http.post("*/administration/operator/gate-dry-run", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      http.delete("*/agentstore/agents/:id", () => new HttpResponse(null, { status: 200 })),
    );

    const error = String(await enforceWriteCanaryGate(config(), spec()).catch((e: unknown) => e));

    expect(error).toMatch(/could not verify the approval gate/i);
    expect(error).toMatch(/deterministic check failed/i);
    expect(error).not.toMatch(/did NOT hold/);
  });

  it("says the gate did NOT hold — and does not offer a retry — on a confirmed fail", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    server.use(http.delete("*/agentstore/agents/:id", () => new HttpResponse(null, { status: 200 })));

    const error = String(await enforceWriteCanaryGate(config(), spec()).catch((e: unknown) => e));

    expect(error).toMatch(/did NOT hold/);
    // Nudging a retry at a broken gate is the one thing this must never do.
    expect(error).not.toMatch(/try activating again/i);
    expect(error).toMatch(/do not re-activate with write access/i);
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

  it("says the operator is STILL DEPLOYED when the rollback itself fails", async () => {
    // The one path the admin has to act on. Letting the rollback's own error
    // propagate would surface a bare transport message for what is actually
    // "a write-capable operator that failed its gate check is still live" —
    // read as a retryable blip, and the agent is never removed.
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    server.use(
      // The DELETE, not the undeploy: resetOperator deliberately tolerates a
      // failed undeploy (already undeployed is fine — deletion is the point),
      // so only a failed delete actually leaves the agent standing.
      http.delete("*/agentstore/agents/:id", () =>
        HttpResponse.json({ message: "backend down" }, { status: 500 }),
      ),
    );

    const error = await enforceWriteCanaryGate(config(), spec()).catch((e: unknown) => e);

    expect(String(error)).toMatch(/still deployed/i);
    expect(String(error)).toMatch(/remove it manually/i);
    // The original reason must survive too — the admin needs both facts.
    expect(String(error)).toMatch(/did NOT hold/);
  });
});

describe("stream ends without a done frame", () => {
  it("reports unknown, never fail — a dropped stream proves nothing about the gate", async () => {
    // Only tokens, then the stream closes: no done frame, no final state. A
    // "fail" verdict here would tear down a healthy operator on a network
    // blip; the composite check's deterministic verdict carries the decision.
    serveTurn(["event: token\ndata: working on it\n\n"]);

    const result = await runOperatorWriteCanary(config(), spec());

    expect(result.outcome).toBe("unknown");
    expect(result.error).toMatch(/without a final conversation state/i);
  });
});
