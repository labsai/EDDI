import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  readOperatorConfig,
  writeOperatorConfig,
  provisionOperator,
  resolveAgentVersion,
  parseVersionFromLocation,
  findMissingEndpoints,
  apiAuthForMode,
  defaultOperatorConfig,
  deactivateOperator,
  reactivateOperator,
  assertProvisioned,
  runOperatorCanary,
  verifyGateInstalled,
  gateLooksInstalled,
  OPERATOR_VARIABLE_KEY,
  CALLER_TOKEN_API_AUTH,
  type OperatorConfig,
  type FetchedSpec,
} from "../operator";
import { safetyPreambleForScope } from "@/lib/operator/system-prompt";
import {
  READ_ENDPOINTS,
  WRITE_ENDPOINTS,
  buildToolApprovals,
  buildEndpointFilter,
} from "@/lib/operator/tool-scopes";
import type { Agent } from "../agents";

const BASE = "*/variablestore/variables/default";

function config(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return { ...defaultOperatorConfig("Do the thing."), ...overrides };
}

describe("operator config persistence", () => {
  it("parses the stored JSON blob", async () => {
    const stored = config({ enabled: true, agentId: "abc", version: 3 });
    server.use(
      http.get(`${BASE}/${OPERATOR_VARIABLE_KEY}`, () =>
        HttpResponse.json({
          key: OPERATOR_VARIABLE_KEY,
          value: JSON.stringify(stored),
        }),
      ),
    );
    await expect(readOperatorConfig()).resolves.toEqual(stored);
  });

  it("returns null when the operator was never activated", async () => {
    server.use(
      http.get(`${BASE}/${OPERATOR_VARIABLE_KEY}`, () =>
        HttpResponse.json({ message: "not found" }, { status: 404 }),
      ),
    );
    await expect(readOperatorConfig()).resolves.toBeNull();
  });

  it("treats a corrupt blob as not configured rather than throwing", async () => {
    server.use(
      http.get(`${BASE}/${OPERATOR_VARIABLE_KEY}`, () =>
        HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: "{not json" }),
      ),
    );
    await expect(readOperatorConfig()).resolves.toBeNull();
  });

  it("propagates real errors instead of hiding them as 'not configured'", async () => {
    server.use(
      http.get(`${BASE}/${OPERATOR_VARIABLE_KEY}`, () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    await expect(readOperatorConfig()).rejects.toBeDefined();
  });

  it("writes one atomic blob and marks it non-exportable", async () => {
    let body: Record<string, unknown> | undefined;
    server.use(
      http.put(`${BASE}/${OPERATOR_VARIABLE_KEY}`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const cfg = config({ enabled: true, agentId: "a1", version: 2 });
    await writeOperatorConfig(cfg);

    expect(body?.key).toBe(OPERATOR_VARIABLE_KEY);
    expect(body?.exportable).toBe(false);
    expect(JSON.parse(String(body?.value))).toEqual(cfg);
  });
});

describe("version resolution", () => {
  it("parses the version out of the created agent location", () => {
    expect(parseVersionFromLocation("/agentstore/agents/abc?version=7")).toBe(7);
  });

  it("returns null for a location without a version", () => {
    expect(parseVersionFromLocation("/agentstore/agents/abc")).toBeNull();
  });

  it("prefers the location and never calls currentversion", async () => {
    let currentVersionCalled = false;
    server.use(
      http.get("*/agentstore/agents/:id/currentversion", () => {
        currentVersionCalled = true;
        return HttpResponse.json(99);
      }),
    );
    const version = await resolveAgentVersion({
      action: "api_agent_created",
      agentId: "abc",
      agentName: "Op",
      provider: "anthropic",
      model: "m",
      resources: { agentLocation: "/agentstore/agents/abc?version=4" },
    });
    expect(version).toBe(4);
    expect(currentVersionCalled).toBe(false);
  });

  it("falls back to currentversion when the location has none", async () => {
    server.use(
      http.get("*/agentstore/agents/:id/currentversion", () =>
        HttpResponse.json(12),
      ),
    );
    const version = await resolveAgentVersion({
      action: "api_agent_created",
      agentId: "abc",
      agentName: "Op",
      provider: "anthropic",
      model: "m",
      resources: {},
    });
    expect(version).toBe(12);
  });
});

describe("spec validation", () => {
  const spec: FetchedSpec = {
    raw: {},
    paths: {
      "/administration/logs": { get: {}, post: {} },
      "/agentstore/agents/{id}": { get: {} },
    },
  };

  it("reports nothing missing when every endpoint is present", () => {
    expect(
      findMissingEndpoints(spec, ["GET /administration/logs", "GET /agentstore/agents/{id}"]),
    ).toEqual([]);
  });

  it("reports a path the deployment does not expose", () => {
    expect(findMissingEndpoints(spec, ["GET /not/there"])).toEqual(["GET /not/there"]);
  });

  it("reports a path that exists but lacks the verb", () => {
    // Binding DELETE against a GET-only path would silently produce no tool.
    expect(findMissingEndpoints(spec, ["DELETE /administration/logs"])).toEqual([
      "DELETE /administration/logs",
    ]);
  });

  it("reports malformed entries rather than skipping them", () => {
    expect(findMissingEndpoints(spec, ["garbage"])).toEqual(["garbage"]);
  });
});

describe("auth mode", () => {
  it("sends no Authorization header in 'none' mode", () => {
    expect(apiAuthForMode("none")).toBeUndefined();
  });

  it("uses the backend's caller reference, resolved per call", () => {
    // EDDI's CallerIdentityResolver substitutes this while building the
    // request, so the token never travels through conversation context.
    expect(apiAuthForMode("caller-identity")).toBe(CALLER_TOKEN_API_AUTH);
    expect(apiAuthForMode("caller-identity")).toBe("Bearer ${caller:token}");
  });
});

describe("provisionOperator", () => {
  const specBody = { openapi: "3.1.0", paths: { "/administration/logs": { get: {} } } };
  const fetchedSpec = (): FetchedSpec => ({ raw: specBody, paths: specBody.paths });
  let captured: Record<string, unknown> | undefined;

  beforeEach(() => {
    captured = undefined;
    vi.stubGlobal("location", { ...globalThis.location, origin: "https://eddi.example" });
    server.use(
      http.get("*/openapi", () => HttpResponse.json(specBody)),
      http.post("*/administration/agents/setup-api", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            action: "api_agent_created",
            agentId: "op-1",
            agentName: captured.agentName,
            provider: "anthropic",
            model: "m",
            resources: { agentLocation: "/agentstore/agents/op-1?version=1" },
          },
          { status: 201 },
        );
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends the name as agentName, the field the backend requires", async () => {
    await provisionOperator({
      agentName: "EDDI Platform Operator",
      config: config(),
      apiKey: "sk-test",
      spec: fetchedSpec(),
    });
    expect(captured?.agentName).toBe("EDDI Platform Operator");
    expect(captured).not.toHaveProperty("name");
  });

  it("prepends the non-editable safety preamble to the editable body", async () => {
    await provisionOperator({
      agentName: "Op",
      config: config({ promptBody: "Custom body." }),
      apiKey: "sk-test",
      spec: fetchedSpec(),
    });
    const prompt = String(captured?.systemPrompt);
    // The default scope is read_write, so the default preamble is the
    // approval-gated write one.
    expect(prompt.startsWith(safetyPreambleForScope("read_write"))).toBe(true);
    expect(prompt).toContain("Custom body.");
  });

  it("builds the preamble for the scope it sends the endpoint filter for", async () => {
    // The pairing is the point: whichever scope is provisioned, the prompt has
    // to describe the SAME endpoint filter that was actually sent, or the
    // agent is told about a capability boundary it is not really behind.
    await provisionOperator({
      agentName: "Op",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
      spec: fetchedSpec(),
    });
    const prompt = String(captured?.systemPrompt);
    expect(prompt.startsWith(safetyPreambleForScope("read_write"))).toBe(true);
    expect(String(captured?.endpoints)).toBe(buildEndpointFilter("read_write"));
  });

  it("sends the full spec untrimmed", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test", spec: fetchedSpec() });
    expect(JSON.parse(String(captured?.openApiSpec))).toEqual(specBody);
  });

  it("scopes a read_only config's tools to the read allow-list", async () => {
    await provisionOperator({
      agentName: "Op",
      config: config({ scope: "read_only" }),
      apiKey: "sk-test",
      spec: fetchedSpec(),
    });
    const endpoints = String(captured?.endpoints);
    for (const entry of READ_ENDPOINTS) {
      expect(endpoints).toContain(entry);
    }
    expect(endpoints).not.toMatch(/\b(POST|PUT|DELETE|PATCH)\b/);
  });

  it("grants the write allow-list for the default (read_write) config", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test", spec: fetchedSpec() });
    const endpoints = String(captured?.endpoints);
    for (const entry of [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]) {
      expect(endpoints).toContain(entry);
    }
  });

  it("bakes no credential into the tools in 'none' mode", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test", spec: fetchedSpec() });
    expect(captured?.apiAuth).toBeUndefined();
  });

  it("uses the caller-token placeholder in caller-context mode", async () => {
    await provisionOperator({
      agentName: "Op",
      config: config({ authMode: "caller-identity" }),
      apiKey: "sk-test",
      spec: fetchedSpec(),
    });
    expect(captured?.apiAuth).toBe(CALLER_TOKEN_API_AUTH);
  });

  it("targets the current origin and deploys", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test", spec: fetchedSpec() });
    expect(captured?.apiBaseUrl).toBe("https://eddi.example");
    expect(captured?.deploy).toBe(true);
  });

  it("sends a local provider's URL as the LLM base URL, not as the tool target", async () => {
    // apiBaseUrl is the target server of the generated tools. Sending Ollama's
    // URL there pointed every operator tool at the local model server.
    await provisionOperator({
      agentName: "Op",
      config: config({ provider: "ollama" }),
      apiKey: "",
      baseUrl: "http://localhost:11434",
      spec: fetchedSpec(),
    });
    expect(captured?.llmBaseUrl).toBe("http://localhost:11434");
    expect(captured?.apiBaseUrl).toBe("https://eddi.example");
  });

  it("always targets this deployment, whatever the provider", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test", spec: fetchedSpec() });
    expect(captured?.apiBaseUrl).toBe("https://eddi.example");
    expect(captured?.llmBaseUrl).toBeUndefined();
  });

  it("sends the tool-approval gate even for read_only", async () => {
    // The whole point of installing it now: read_only proves the pipeline
    // end-to-end at zero risk, and read_write later reuses the identical config.
    await provisionOperator({
      agentName: "Op",
      config: config({ scope: "read_only" }),
      apiKey: "sk-test",
      spec: fetchedSpec(),
    });
    const hitlConfig = captured?.hitlConfig as { toolApprovals?: unknown } | undefined;
    expect(hitlConfig?.toolApprovals).toEqual(buildToolApprovals());
  });

  it("never sends an AUTO_APPROVE timeout policy", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test", spec: fetchedSpec() });
    const hitlConfig = captured?.hitlConfig as
      | { timeoutPolicy?: string; toolApprovals?: { timeoutPolicy?: string } }
      | undefined;
    expect(hitlConfig?.timeoutPolicy).not.toBe("AUTO_APPROVE");
    expect(hitlConfig?.toolApprovals?.timeoutPolicy).not.toBe("AUTO_APPROVE");
  });
});

describe("gateLooksInstalled", () => {
  function agentWithGate(overrides: Partial<NonNullable<Agent["hitlConfig"]>> = {}): Agent {
    return {
      hitlConfig: {
        toolApprovals: buildToolApprovals(),
        ...overrides,
      },
    };
  }

  it("accepts what buildToolApprovals actually produces", () => {
    expect(gateLooksInstalled(agentWithGate()).ok).toBe(true);
  });

  it("rejects an agent with no hitlConfig at all", () => {
    const result = gateLooksInstalled({});
    expect(result.ok).toBe(false);
    expect(result.reason).toMatch(/hitlConfig is absent/);
  });

  it("rejects a populated requireApproval that gates only reads", () => {
    // A decoy: non-empty, so the length check alone accepts it, while every
    // write on the agent runs unapproved. This is what "the gate is verified"
    // would otherwise have certified as sound.
    const result = gateLooksInstalled({
      hitlConfig: { toolApprovals: { requireApproval: ["http.get:*"], exempt: [] } },
    });
    expect(result.ok).toBe(false);
    expect(result.reason).toMatch(/gates no write method/);
  });

  it("accepts a broad wildcard as gating writes", () => {
    // The mirror direction, so the check above is not simply "reject anything
    // unfamiliar": `*` and `http.*:*` both genuinely cover the write methods.
    expect(gateLooksInstalled({ hitlConfig: { toolApprovals: { requireApproval: ["*"] } } }).ok).toBe(true);
    expect(gateLooksInstalled({ hitlConfig: { toolApprovals: { requireApproval: ["http.*:*"] } } }).ok).toBe(true);
  });

  it("rejects hitlConfig with no toolApprovals", () => {
    const result = gateLooksInstalled({ hitlConfig: {} });
    expect(result.ok).toBe(false);
    expect(result.reason).toMatch(/toolApprovals is absent/);
  });

  it("rejects an empty requireApproval — the gate would be inactive", () => {
    const result = gateLooksInstalled(
      agentWithGate({ toolApprovals: { ...buildToolApprovals(), requireApproval: [] } }),
    );
    expect(result.ok).toBe(false);
    expect(result.reason).toMatch(/requireApproval is empty/);
  });

  it("rejects agent-level AUTO_APPROVE", () => {
    const result = gateLooksInstalled(agentWithGate({ timeoutPolicy: "AUTO_APPROVE" }));
    expect(result.ok).toBe(false);
    expect(result.reason).toMatch(/hitlConfig.timeoutPolicy is AUTO_APPROVE/);
  });

  it("rejects tool-level AUTO_APPROVE", () => {
    const result = gateLooksInstalled(
      agentWithGate({ toolApprovals: { ...buildToolApprovals(), timeoutPolicy: "AUTO_APPROVE" } }),
    );
    expect(result.ok).toBe(false);
    expect(result.reason).toMatch(/toolApprovals.timeoutPolicy is AUTO_APPROVE/);
  });

  it.each(["http.post:*", "http.put:*", "http.patch:*", "http.delete:*", "*", "http.*", "http.*:*"])(
    "rejects an exempt pattern that would swallow a gated write: %s",
    (overbroad) => {
      const result = gateLooksInstalled(
        agentWithGate({ toolApprovals: { ...buildToolApprovals(), exempt: [overbroad] } }),
      );
      expect(result.ok).toBe(false);
      expect(result.reason).toContain(overbroad);
    },
  );

  it.each([
    "http.post:/agentstore/agents",
    "http.put:/llmstore/llms/{id}",
    "http.patch:/descriptorstore/descriptors/{id}",
    "http.delete:/schedulestore/schedules/{scheduleId}",
    "http.*:/agentstore/agents",
  ])("rejects a NARROW exempt that un-gates one write: %s", (narrow) => {
    // The dangerous direction is not only the obviously-broad pattern. An
    // exempt naming a single write endpoint reads as a targeted allowance and
    // is strictly worse than an AUTO_APPROVE rule for the same call:
    // ToolApprovalGate.classify tests `exempt` first and short-circuits, so the
    // call never pauses at all rather than pausing and self-approving.
    // `http.*:` is included because the method segment is a wildcard, and the
    // compiled glob turns `*` into `.*` — it matches the POST address too.
    const result = gateLooksInstalled(
      agentWithGate({ toolApprovals: { ...buildToolApprovals(), exempt: [narrow] } }),
    );
    expect(result.ok).toBe(false);
    expect(result.reason).toContain(narrow);
  });

  it("still accepts the read exemption buildToolApprovals actually writes", () => {
    // Guards the fix above from over-reaching: `http.get:*` shares the `http.`
    // prefix with every gated write pattern and must not be swept up.
    expect(gateLooksInstalled(agentWithGate({ toolApprovals: { ...buildToolApprovals(), exempt: ["http.get:*"] } })).ok).toBe(true);
    expect(gateLooksInstalled(agentWithGate({ toolApprovals: { ...buildToolApprovals(), exempt: ["http.get:/administration/logs"] } })).ok).toBe(true);
  });

  it("rejects a per-tool rule that AUTO_APPROVEs a gated write endpoint", () => {
    // The scalar toolApprovals.timeoutPolicy looks safe (buildToolApprovals
    // never sets it to AUTO_APPROVE) — but ToolApprovalRules.governing on the
    // backend lets a matching rule override it for the calls it addresses. A
    // check that only reads the scalar would pass this document while one
    // endpoint actually auto-executes unreviewed.
    const result = gateLooksInstalled(
      agentWithGate({
        toolApprovals: {
          ...buildToolApprovals(),
          rules: [{ match: "http.post:/agentstore/agents", timeoutPolicy: "AUTO_APPROVE" }],
        },
      }),
    );
    expect(result.ok).toBe(false);
    expect(result.reason).toContain("http.post:/agentstore/agents");
    expect(result.reason).toMatch(/AUTO_APPROVE/);
  });

  it.each(["http.post:*", "http.put:/llmstore/llms/{id}", "http.patch:*", "http.delete:*"])(
    "catches AUTO_APPROVE on a broad or narrow rule targeting a write method: %s",
    (match) => {
      const result = gateLooksInstalled(
        agentWithGate({ toolApprovals: { ...buildToolApprovals(), rules: [{ match, timeoutPolicy: "AUTO_APPROVE" }] } }),
      );
      expect(result.ok).toBe(false);
    },
  );

  it("does not flag a rule that names a write endpoint but keeps it strict", () => {
    const result = gateLooksInstalled(
      agentWithGate({
        toolApprovals: {
          ...buildToolApprovals(),
          rules: [{ match: "http.delete:*", timeoutPolicy: "WAIT_INDEFINITELY" }],
        },
      }),
    );
    expect(result.ok).toBe(true);
  });

  it("does not flag an AUTO_APPROVE rule that targets a READ, not a write", () => {
    // Auto-approving a GET is a friction choice, not a safety hole — GET is
    // exempt from the gate entirely, so this rule can never fire on anything
    // requireApproval would have gated in the first place.
    const result = gateLooksInstalled(
      agentWithGate({
        toolApprovals: {
          ...buildToolApprovals(),
          rules: [{ match: "http.get:*", timeoutPolicy: "AUTO_APPROVE" }],
        },
      }),
    );
    expect(result.ok).toBe(true);
  });

  it("does not flag a rule with no timeoutPolicy (message/reason-only rules are fine)", () => {
    const result = gateLooksInstalled(
      agentWithGate({
        toolApprovals: {
          ...buildToolApprovals(),
          rules: [{ match: "http.post:/agentstore/agents", pauseReason: "Creating a new agent" }],
        },
      }),
    );
    expect(result.ok).toBe(true);
  });

  it("does not flag the narrow exempt pattern buildToolApprovals actually uses", () => {
    expect(gateLooksInstalled(agentWithGate()).ok).toBe(true);
  });
});

describe("verifyGateInstalled", () => {
  function mockAgentVersions(agentId: string, versions: Record<number, Agent>, currentVersion: number) {
    server.use(
      http.get(`*/agentstore/agents/${agentId}/currentversion`, () => HttpResponse.json(currentVersion)),
      http.get(`*/agentstore/agents/${agentId}`, ({ request }) => {
        const url = new URL(request.url);
        const version = Number(url.searchParams.get("version") ?? "0");
        const agent = versions[version];
        if (!agent) return new HttpResponse(null, { status: 404 });
        return HttpResponse.json(agent);
      }),
    );
  }

  it("verifies when the single version carries a sane gate", async () => {
    const gated: Agent = { hitlConfig: { toolApprovals: buildToolApprovals() } };
    mockAgentVersions("agent-1", { 1: gated }, 1);
    const result = await verifyGateInstalled("agent-1");
    expect(result.verified).toBe(true);
    expect(result.checkedVersions).toEqual([1]);
  });

  it("refuses when an EARLIER version lacks the gate, even though the latest has it", async () => {
    // The whole reason to check every version: a redeploy can reach any
    // previously-created version, not just the one currently live.
    const gated: Agent = { hitlConfig: { toolApprovals: buildToolApprovals() } };
    const ungated: Agent = {};
    mockAgentVersions("agent-1", { 1: ungated, 2: gated }, 2);
    const result = await verifyGateInstalled("agent-1");
    expect(result.verified).toBe(false);
    expect(result.reason).toMatch(/^version 1:/);
    expect(result.checkedVersions).toEqual([1]);
  });

  it("checks every version up to current, in order, stopping at the first failure", async () => {
    const gated: Agent = { hitlConfig: { toolApprovals: buildToolApprovals() } };
    mockAgentVersions("agent-1", { 1: gated, 2: gated, 3: {} }, 3);
    const result = await verifyGateInstalled("agent-1");
    expect(result.verified).toBe(false);
    expect(result.checkedVersions).toEqual([1, 2, 3]);
    expect(result.reason).toMatch(/^version 3:/);
  });

  it("reports a network failure as unverified rather than throwing", async () => {
    server.use(
      http.get("*/agentstore/agents/agent-1/currentversion", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    await expect(verifyGateInstalled("agent-1")).resolves.toMatchObject({
      verified: false,
      checkedVersions: [],
    });
  });

  it("reports an unresolvable version as unverified", async () => {
    server.use(
      http.get("*/agentstore/agents/agent-1/currentversion", () => HttpResponse.json(0)),
    );
    const result = await verifyGateInstalled("agent-1");
    expect(result.verified).toBe(false);
    expect(result.checkedVersions).toEqual([]);
  });
});

describe("deactivateOperator", () => {
  it("undeploys with the stored version and disables the config", async () => {
    let undeployUrl = "";
    let saved: OperatorConfig | undefined;
    server.use(
      http.post("*/administration/:env/undeploy/:agentId", ({ request }) => {
        undeployUrl = request.url;
        return new HttpResponse(null, { status: 200 });
      }),
      http.put(`${BASE}/${OPERATOR_VARIABLE_KEY}`, async ({ request }) => {
        const body = (await request.json()) as { value: string };
        saved = JSON.parse(body.value);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const result = await deactivateOperator(
      config({ enabled: true, agentId: "op-1", version: 5, environment: "test" }),
    );

    // The endpoint rejects a versionless undeploy, so the version must be threaded through.
    expect(undeployUrl).toContain("/administration/test/undeploy/op-1");
    expect(undeployUrl).toContain("version=5");
    // Without this flag the backend 409s whenever the operator has an active
    // conversation — and the admin's own operator chat IS one, so having used
    // the operator at all made the kill switch fail with a bare 409. The
    // conversations ended are this operator's own; the admin is explicitly
    // shutting it down.
    expect(undeployUrl).toContain("endAllActiveConversations=true");
    expect(result.enabled).toBe(false);
    expect(saved?.enabled).toBe(false);
    // The agent pointer survives so the operator can be switched back on.
    expect(saved?.agentId).toBe("op-1");
  });

  it("still disables the config when no agent was ever provisioned", async () => {
    server.use(
      http.put(`${BASE}/${OPERATOR_VARIABLE_KEY}`, () => new HttpResponse(null, { status: 204 })),
    );
    const result = await deactivateOperator(config({ enabled: true }));
    expect(result.enabled).toBe(false);
  });
});

describe("assertProvisioned", () => {
  const base = {
    action: "api_agent_created",
    agentId: "op-1",
    agentName: "Op",
    provider: "anthropic",
    model: "m",
  };

  it("accepts a deployed agent with a real id", () => {
    expect(() => assertProvisioned({ ...base, deployed: true })).not.toThrow();
  });

  // setup-api falls back to the literal string "unknown" when it cannot read
  // the created agent's location; storing that would break status and undeploy.
  it("rejects the placeholder id", () => {
    expect(() => assertProvisioned({ ...base, agentId: "unknown" })).toThrow(/agent id/i);
  });

  it("rejects an empty id", () => {
    expect(() => assertProvisioned({ ...base, agentId: "" })).toThrow(/agent id/i);
  });

  // 201 is returned even when the deploy step failed.
  it("rejects a created-but-undeployed agent", () => {
    expect(() => assertProvisioned({ ...base, deployed: false })).toThrow(/deploy/i);
  });

  it("rejects an ERROR deployment status", () => {
    expect(() =>
      assertProvisioned({ ...base, deployed: true, deploymentStatus: "ERROR" }),
    ).toThrow(/ERROR/);
  });

  it("accepts a result that simply omits the deploy fields", () => {
    expect(() => assertProvisioned(base)).not.toThrow();
  });
});

describe("runOperatorCanary", () => {
  const cfg = () => config({ enabled: true, agentId: "op-1", version: 1 });

  /** Serve a start + a stream made of the given SSE frames. */
  function serveTurn(frames: string[]) {
    server.use(
      http.post("*/agents/:agentId/start", () =>
        HttpResponse.json(
          { location: "/agents/conv-1" },
          { status: 201, headers: { Location: "/agents/conv-1" } },
        ),
      ),
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse(frames.join(""), {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        }),
      ),
    );
  }

  const taskComplete = (trace: unknown) =>
    `event: task_complete\ndata: ${JSON.stringify({ taskId: "t", taskType: "ai.labs.llm", index: 0, toolTrace: trace })}\n\n`;

  it("passes when the operator actually called a tool", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "getAgents" },
        { type: "tool_result", tool: "getAgents", result: '[{"id":"a1"}]' },
      ]),
      "event: done\ndata: \n\n",
    ]);
    const result = await runOperatorCanary(cfg());
    expect(result.ok).toBe(true);
    expect(result.toolCalls).toBe(1);
  });

  // The whole point of the probe: a model that answers from thin air looks
  // identical to a working operator unless tool calls are counted.
  it("fails when the operator answered without calling any tool", async () => {
    serveTurn([
      "event: token\ndata: You have three agents.\n\n",
      "event: done\ndata: \n\n",
    ]);
    const result = await runOperatorCanary(cfg());
    expect(result.ok).toBe(false);
    expect(result.toolCalls).toBe(0);
    expect(result.error).toMatch(/without calling any tool/i);
  });

  // This is the exact shape a wrong authMode produces: deployed, responsive,
  // tools invoked, every one of them rejected.
  it("fails when the tools were rejected as unauthorized", async () => {
    serveTurn([
      taskComplete([
        { type: "tool_call", tool: "getAgents" },
        { type: "tool_result", tool: "getAgents", result: "HTTP 401 Unauthorized" },
      ]),
      "event: done\ndata: \n\n",
    ]);
    const result = await runOperatorCanary(cfg());
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/unauthorized/i);
  });

  it("reports an in-band stream error", async () => {
    serveTurn(["event: error\ndata: model provider rejected the key\n\n"]);
    const result = await runOperatorCanary(cfg());
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/model provider/i);
  });

  it("reports a transport failure instead of throwing", async () => {
    server.use(
      http.post("*/agents/:agentId/start", () =>
        HttpResponse.json({ message: "boom" }, { status: 500 }),
      ),
    );
    const result = await runOperatorCanary(cfg());
    expect(result.ok).toBe(false);
    expect(result.error).toBeTruthy();
  });

  it("fails cleanly when no agent is configured", async () => {
    const result = await runOperatorCanary(config());
    expect(result.ok).toBe(false);
    expect(result.error).toMatch(/no operator agent/i);
  });
});

describe("reactivateOperator", () => {
  it("redeploys the existing agent and re-enables the config", async () => {
    let deployUrl = "";
    let saved: OperatorConfig | undefined;
    server.use(
      http.post("*/administration/:env/deploy/:agentId", ({ request }) => {
        deployUrl = request.url;
        return new HttpResponse(null, { status: 200 });
      }),
      http.put(`${BASE}/${OPERATOR_VARIABLE_KEY}`, async ({ request }) => {
        const body = (await request.json()) as { value: string };
        saved = JSON.parse(body.value);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const result = await reactivateOperator(
      config({ enabled: false, agentId: "op-1", version: 3, environment: "test" }),
    );

    expect(deployUrl).toContain("/administration/test/deploy/op-1");
    expect(deployUrl).toContain("version=3");
    expect(result.enabled).toBe(true);
    expect(saved?.agentId).toBe("op-1");
  });

  it("refuses when there is no provisioned agent to redeploy", async () => {
    await expect(reactivateOperator(config())).rejects.toThrow(/no provisioned agent/i);
  });
});

describe("readOperatorConfig — malformed blob shapes", () => {
  // JSON.parse succeeds for these; casting one to OperatorConfig would surface
  // downstream as undefined property reads rather than "not configured".
  it.each([
    ["a JSON null", "null"],
    ["a bare number", "42"],
    ["a bare string", '"not a config"'],
    ["an array", "[1,2,3]"],
  ])("treats %s as not configured", async (_label, value) => {
    server.use(
      http.get(`${BASE}/${OPERATOR_VARIABLE_KEY}`, () =>
        HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value }),
      ),
    );
    await expect(readOperatorConfig()).resolves.toBeNull();
  });
});
