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
  OPERATOR_VARIABLE_KEY,
  CALLER_TOKEN_API_AUTH,
  type OperatorConfig,
  type FetchedSpec,
} from "../operator";
import { OPERATOR_SAFETY_PREAMBLE } from "@/lib/operator/system-prompt";
import { READ_ENDPOINTS } from "@/lib/operator/tool-scopes";

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

  it("uses a template placeholder resolved per turn in caller-context mode", () => {
    expect(apiAuthForMode("caller-context")).toBe(CALLER_TOKEN_API_AUTH);
    expect(apiAuthForMode("caller-context")).toContain("{context.");
  });
});

describe("provisionOperator", () => {
  const specBody = { openapi: "3.1.0", paths: { "/administration/logs": { get: {} } } };
  let captured: Record<string, unknown> | undefined;

  beforeEach(() => {
    captured = undefined;
    vi.stubGlobal("window", { ...globalThis.window, location: { origin: "https://eddi.example" } });
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
    });
    expect(captured?.agentName).toBe("EDDI Platform Operator");
    expect(captured).not.toHaveProperty("name");
  });

  it("prepends the non-editable safety preamble to the editable body", async () => {
    await provisionOperator({
      agentName: "Op",
      config: config({ promptBody: "Custom body." }),
      apiKey: "sk-test",
    });
    const prompt = String(captured?.systemPrompt);
    expect(prompt.startsWith(OPERATOR_SAFETY_PREAMBLE)).toBe(true);
    expect(prompt).toContain("Custom body.");
  });

  it("sends the full spec untrimmed", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test" });
    expect(JSON.parse(String(captured?.openApiSpec))).toEqual(specBody);
  });

  it("scopes tools to the read allow-list", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test" });
    const endpoints = String(captured?.endpoints);
    for (const entry of READ_ENDPOINTS) {
      expect(endpoints).toContain(entry);
    }
    expect(endpoints).not.toMatch(/\b(POST|PUT|DELETE|PATCH)\b/);
  });

  it("bakes no credential into the tools in 'none' mode", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test" });
    expect(captured?.apiAuth).toBeUndefined();
  });

  it("uses the caller-token placeholder in caller-context mode", async () => {
    await provisionOperator({
      agentName: "Op",
      config: config({ authMode: "caller-context" }),
      apiKey: "sk-test",
    });
    expect(captured?.apiAuth).toBe(CALLER_TOKEN_API_AUTH);
  });

  it("targets the current origin and deploys", async () => {
    await provisionOperator({ agentName: "Op", config: config(), apiKey: "sk-test" });
    expect(captured?.apiBaseUrl).toBe("https://eddi.example");
    expect(captured?.deploy).toBe(true);
  });

  it("honours an explicit base URL for local providers", async () => {
    await provisionOperator({
      agentName: "Op",
      config: config({ provider: "ollama" }),
      apiKey: "",
      baseUrl: "http://localhost:11434",
    });
    expect(captured?.apiBaseUrl).toBe("http://localhost:11434");
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
