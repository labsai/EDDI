import { describe, it, expect, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  runOperatorWriteCanary,
  enforceGateDryRun,
  runBackgroundWriteProbe,
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
    // Not "no agents to test against" — the probe self-targets the operator's
    // own descriptor, so the target always exists; the model just declined.
    expect(result.error).toMatch(/declined to attempt the test write/i);
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
    // The report must carry a duration at all — `>= 0` was true of any number
    // and of the `0` a missing field coerces to.
    expect(typeof (canaryReports[0] as { durationMs: number }).durationMs).toBe("number");
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

describe("enforceGateDryRun — the blocking, deterministic half", () => {
  const VAR_URL = "*/variablestore/variables/default/platform.operator";

  beforeEach(() => {
    server.use(
      http.post("*/administration/operator/canary-result", () => new HttpResponse(null, { status: 204 })),
      http.post("*/agents/:conversationId/endConversation", () => new HttpResponse(null, { status: 200 })),
    );
  });

  it("is a no-op for read_only — nothing is called, nothing is deleted", async () => {
    let dryRunCalled = false;
    server.use(
      http.post("*/administration/operator/gate-dry-run", () => {
        dryRunCalled = true;
        return HttpResponse.json({ policyPresent: true, gated: true, matchedPattern: "http.patch:*" });
      }),
    );

    const result = await enforceGateDryRun(config({ scope: "read_only" }), spec());

    expect(result).toBeNull();
    expect(dryRunCalled).toBe(false);
  });

  it("returns true — verified — when the dry-run classifies the target write as gated", async () => {
    let deleted = false;
    server.use(
      http.post("*/administration/operator/gate-dry-run", () =>
        HttpResponse.json({ policyPresent: true, gated: true, matchedPattern: "http.patch:*" }),
      ),
      http.delete("*/agentstore/agents/:id", () => {
        deleted = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    await expect(enforceGateDryRun(config(), spec())).resolves.toBe(true);
    expect(deleted).toBe(false);
  });

  /**
   * Deterministically broken configuration: the one write-verification outcome
   * that still blocks activation, because it is PROOF, not absence of proof.
   */
  it("rolls back and throws when the dry-run says the write is not gated", async () => {
    let undeployed = false;
    let deleted = false;
    let configCleared = false;
    server.use(
      http.post("*/administration/operator/gate-dry-run", () =>
        HttpResponse.json({ policyPresent: true, gated: false, matchedPattern: null }),
      ),
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

    const error = String(await enforceGateDryRun(config(), spec()).catch((e: unknown) => e));

    expect(error).toMatch(/did NOT hold/);
    expect(error).toMatch(/no probe was run and nothing was written/i);
    expect(undeployed).toBe(true);
    expect(deleted).toBe(true);
    expect(configCleared).toBe(true);
    // Pins the RollbackFailure re-throw guard: without it the rollback's own
    // throw is caught again and re-wrapped, so the admin reads a generic
    // "could not verify / deterministic check failed" headline instead of the
    // proven-broken-gate one (and the operator is rolled back twice).
    expect(error).not.toMatch(/deterministic check failed/i);
    expect(error).not.toMatch(/could not verify/i);
  });

  it("fails closed when the dry-run itself errors (not 404) — verification failure, not breach", async () => {
    server.use(
      http.post("*/administration/operator/gate-dry-run", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
      http.delete("*/agentstore/agents/:id", () => new HttpResponse(null, { status: 200 })),
      http.delete(VAR_URL, () => new HttpResponse(null, { status: 204 })),
      http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
    );

    const error = String(await enforceGateDryRun(config(), spec()).catch((e: unknown) => e));

    expect(error).toMatch(/could not verify the approval gate/i);
    expect(error).toMatch(/deterministic check failed/i);
    expect(error).not.toMatch(/did NOT hold/);
    // An admin left with no operator needs a way forward, not just a verdict.
    expect(error).toMatch(/try activating again/i);
    expect(error).toMatch(/read-only/i);
  });

  /**
   * A backend that predates gate-dry-run (404) can no longer be verified
   * deterministically — activation proceeds UNVERIFIED (false) rather than
   * failing, and the background probe becomes the deployment's only evidence.
   */
  it("returns false — unverified, not broken — on a 404 old backend, and deletes nothing", async () => {
    let deleted = false;
    server.use(
      http.post("*/administration/operator/gate-dry-run", () => new HttpResponse(null, { status: 404 })),
      http.delete("*/agentstore/agents/:id", () => {
        deleted = true;
        return new HttpResponse(null, { status: 200 });
      }),
    );

    await expect(enforceGateDryRun(config(), spec())).resolves.toBe(false);
    expect(deleted).toBe(false);
  });

  it("says the operator is STILL DEPLOYED when the rollback itself fails", async () => {
    server.use(
      http.post("*/administration/operator/gate-dry-run", () =>
        HttpResponse.json({ policyPresent: false, gated: false, matchedPattern: null }),
      ),
      http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
      // The DELETE, not the undeploy: resetOperator deliberately tolerates a
      // failed undeploy (already undeployed is fine — deletion is the point),
      // so only a failed delete actually leaves the agent standing.
      http.delete("*/agentstore/agents/:id", () =>
        HttpResponse.json({ message: "backend down" }, { status: 500 }),
      ),
    );

    const error = String(await enforceGateDryRun(config(), spec()).catch((e: unknown) => e));

    expect(error).toMatch(/still deployed/i);
    expect(error).toMatch(/remove it manually/i);
    // The original reason must survive too — the admin needs both facts.
    expect(error).toMatch(/did NOT hold/);
  });
});

describe("runBackgroundWriteProbe — the empirical half, after activation", () => {
  const VAR_URL = "*/variablestore/variables/default/platform.operator";

  /** The stored config still names the probe's agent — the teardown's happy precondition. */
  const storedConfigPointsAt = (agentId: string) =>
    http.get(VAR_URL, () =>
      HttpResponse.json({
        key: "platform.operator",
        value: JSON.stringify(config({ agentId })),
      }),
    );

  beforeEach(() => {
    server.use(
      http.post("*/administration/operator/canary-result", () => new HttpResponse(null, { status: 204 })),
      http.post("*/agents/:conversationId/endConversation", () => new HttpResponse(null, { status: 200 })),
    );
  });

  it("is a no-op for read_only — no probe runs, nothing is deleted", async () => {
    let anyProbeRequestMade = false;
    server.use(
      http.post("*/agents/:agentId/start", () => {
        anyProbeRequestMade = true;
        return HttpResponse.json({ location: "/agents/conv-1" }, { status: 201 });
      }),
    );

    const report = await runBackgroundWriteProbe(config({ scope: "read_only" }), spec(), true);

    expect(report).toBeNull();
    expect(anyProbeRequestMade).toBe(false);
  });

  it("reports a clean pass and deletes nothing when the probe's write pauses", async () => {
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

    const report = await runBackgroundWriteProbe(config(), spec(), true);

    expect(report?.result.outcome).toBe("pass");
    expect(report?.tornDown).toBe(false);
    expect(deleteCalled).toBe(false);
  });

  it("tears the operator down — without throwing — when the write executes without pausing", async () => {
    // The write executes without pausing — the gate is broken. This is the
    // scenario the teardown exists for: an agent that is ALREADY deployed,
    // right now, with a write tool that just proved unsafe. The probe runs in
    // the background, so the verdict arrives as a report, not a throw.
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
      storedConfigPointsAt("op-1"),
      http.post("*/administration/:env/undeploy/:agentId", () => {
        undeployed = true;
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete("*/agentstore/agents/:id", ({ request }) => {
        deleted = true;
        expect(request.url).toContain("cascade=true");
        expect(request.url).toContain("permanent=true");
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete(VAR_URL, () => {
        configCleared = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const report = await runBackgroundWriteProbe(config(), spec(), true);

    expect(report?.result.outcome).toBe("fail");
    expect(report?.tornDown).toBe(true);
    expect(report?.message).toMatch(/did NOT hold/);
    expect(report?.message).toMatch(/was removed/i);
    // Nudging a retry at a broken gate is the one thing this must never do.
    expect(report?.message).not.toMatch(/try activating again/i);
    expect(report?.message).toMatch(/do not re-activate with write access/i);
    expect(undeployed).toBe(true);
    expect(deleted).toBe(true);
    expect(configCleared).toBe(true);
  });

  /**
   * The probe runs detached from activation, so a breach verdict can land
   * AFTER the operator was reconfigured (this page, another tab, another
   * admin). A stale probe must remove its own agent — the breach is real —
   * but clearing the shared config variable would erase the REPLACEMENT
   * operator's config.
   */
  it("a stale probe removes its own agent but leaves a successor's config untouched", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    let deleted = false;
    let configCleared = false;
    server.use(
      // The stored config now names a DIFFERENT agent — this probe is stale.
      storedConfigPointsAt("op-2-replacement"),
      http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
      http.delete("*/agentstore/agents/:id", ({ request }) => {
        deleted = true;
        expect(request.url).toContain("/agents/op-1?");
        expect(request.url).toContain("cascade=true");
        expect(request.url).toContain("permanent=true");
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete(VAR_URL, () => {
        configCleared = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const report = await runBackgroundWriteProbe(config(), spec(), true);

    expect(report?.tornDown).toBe(true);
    expect(deleted).toBe(true);
    expect(configCleared).toBe(false);
    expect(report?.message).toMatch(/no longer points at it/i);
    expect(report?.message).toMatch(/left untouched/i);
  });

  it("never clears the shared config on a guess — an unreadable store still only removes the probe's agent", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    let deleted = false;
    let configCleared = false;
    server.use(
      http.get(VAR_URL, () => HttpResponse.json({ message: "store down" }, { status: 500 })),
      http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
      http.delete("*/agentstore/agents/:id", () => {
        deleted = true;
        return new HttpResponse(null, { status: 200 });
      }),
      http.delete(VAR_URL, () => {
        configCleared = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const report = await runBackgroundWriteProbe(config(), spec(), true);

    expect(report?.tornDown).toBe(true);
    expect(deleted).toBe(true);
    expect(configCleared).toBe(false);
    expect(report?.message).toMatch(/could not be read/i);
    expect(report?.message).toMatch(/check the operator screen/i);
  });

  it("says the operator is STILL DEPLOYED when the teardown itself fails", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "patchDescriptor" },
        { type: "tool_result", tool: "patchDescriptor", result: '{"status":"ok"}' },
      ]),
      doneWith("READY"),
    ]);
    server.use(
      storedConfigPointsAt("op-1"),
      http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
      http.delete("*/agentstore/agents/:id", () =>
        HttpResponse.json({ message: "backend down" }, { status: 500 }),
      ),
    );

    const report = await runBackgroundWriteProbe(config(), spec(), true);

    expect(report?.tornDown).toBe(false);
    expect(report?.message).toMatch(/still deployed/i);
    expect(report?.message).toMatch(/remove it manually/i);
    expect(report?.message).toMatch(/did NOT hold/);
  });

  /**
   * THE core semantic property carried over from the dry-run integration: an
   * operator whose stored policy verified deterministically is not deleted
   * just because the model declined to attempt the probe's write.
   */
  it("reports — does NOT tear down — when the policy verified and the probe was merely inconclusive", async () => {
    serveTurn(["event: token\ndata: nothing useful\n\n", doneWith("READY")]);
    let deleted = false;
    server.use(http.delete("*/agentstore/agents/:id", () => { deleted = true; return new HttpResponse(null, { status: 200 }); }));

    const report = await runBackgroundWriteProbe(config(), spec(), true);

    expect(deleted).toBe(false);
    expect(report?.result.outcome).toBe("unknown");
    expect(report?.tornDown).toBe(false);
    // Honest, not upgraded: the caller sees exactly what was and wasn't proven.
    expect(report?.message).toMatch(/verified deterministically/i);
    expect(report?.message).toMatch(/probe was inconclusive/i);
    // ...but marked quiet: with the deterministic verdict in hand, a careful
    // model declining an unexplained write is EXPECTED, and toasting it on
    // every activation trained admins to dismiss operator warnings.
    expect(report?.quiet).toBe(true);
  });

  /**
   * The deliberate semantic CHANGE from the blocking era: on an old backend
   * (no gate-dry-run, nothing verified) an inconclusive probe used to roll the
   * activation back. Now that the probe runs after activation, absence of
   * proof is reported as an honest warning — only PROOF of a broken gate
   * (outcome "fail") tears down a deployed operator.
   */
  it("warns — does NOT tear down — when nothing verified and the probe was inconclusive", async () => {
    serveTurn(["event: token\ndata: nothing useful\n\n", doneWith("READY")]);
    let deleted = false;
    server.use(http.delete("*/agentstore/agents/:id", () => { deleted = true; return new HttpResponse(null, { status: 200 }); }));

    const report = await runBackgroundWriteProbe(config(), spec(), false);

    expect(deleted).toBe(false);
    expect(report?.result.outcome).toBe("unknown");
    expect(report?.tornDown).toBe(false);
    expect(report?.message).toMatch(/not evidence that it is broken/i);
    expect(report?.message).toMatch(/does not support the deterministic check/i);
    // No deterministic verdict to fall back on — the probe was the only signal
    // there was, so this one is NOT quiet.
    expect(report?.quiet).toBeFalsy();
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
