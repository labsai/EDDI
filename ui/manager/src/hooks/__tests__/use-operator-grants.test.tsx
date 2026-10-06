import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, screen, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { useActivateOperator, useReactivateOperator, OperatorGrantPendingError } from "@/hooks/use-operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY, type OperatorConfig } from "@/lib/api/operator";
import { READ_ENDPOINTS, WRITE_ENDPOINTS, parseEndpoint } from "@/lib/operator/tool-scopes";
import { GrantRequiredDialogHost } from "@/components/secrets/grant-required-dialog";

/**
 * Operator activation and the vault grant.
 *
 * Activation provisions with `deploy: false` and deploys through the grant
 * flow, so (1) a deploy that fails for any other reason removes the agent it
 * created instead of leaking it, and (2) a restricted model key is granted to
 * THE agent just created — and a retry after a refused grant reuses that agent
 * rather than creating a second one.
 */

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;
const AGENT_ID = "op-new";
const KEY = "google-gemini-key";

const GOOD_GATE = {
  toolApprovals: {
    requireApproval: ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"],
    exempt: ["http.get:*"],
    timeoutPolicy: "WAIT_INDEFINITELY",
  },
};

function fullSpec() {
  const paths: Record<string, Record<string, unknown>> = {};
  for (const entry of [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]) {
    const parsed = parseEndpoint(entry);
    if (!parsed) continue;
    const methods = (paths[parsed.path] ??= {});
    methods[parsed.method.toLowerCase()] = { operationId: entry };
  }
  return { openapi: "3.0.0", info: { title: "EDDI", version: "6.6.0" }, paths };
}

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      {children}
      <GrantRequiredDialogHost />
    </QueryClientProvider>
  );
}

interface Spy {
  setupCalls: number;
  setupDeployFlags: unknown[];
  deleted: string[];
  configWritten: boolean;
  grants: string[];
  deploys: number;
}

/**
 * Everything activation touches. `preflightIssues` decides whether the model
 * key is restricted; `deployBody` what the waited deploy answers.
 */
function serve(spy: Spy, options: { preflightIssues?: boolean; deployBody?: unknown } = {}) {
  server.use(
    http.get("*/openapi", () => HttpResponse.json(fullSpec())),
    http.get("*/administration/operator/self-url", () =>
      HttpResponse.json({ baseUrl: "http://127.0.0.1:7070", source: "loopback" }),
    ),
    http.post("*/administration/agents/setup-api", async ({ request }) => {
      const body = (await request.json()) as { deploy?: boolean };
      spy.setupCalls += 1;
      spy.setupDeployFlags.push(body.deploy);
      return HttpResponse.json(
        {
          agentId: AGENT_ID,
          deployed: false,
          resources: { agentLocation: `/agentstore/agents/${AGENT_ID}?version=1` },
        },
        { status: 201 },
      );
    }),
    http.get("*/agentstore/agents/:id/currentversion", ({ params }) =>
      spy.deleted.includes(params.id as string)
        ? HttpResponse.json({ error: "gone" }, { status: 404 })
        : HttpResponse.json(1),
    ),
    // Before the by-id read: `:id` would match `descriptors` too.
    http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([])),
    http.get("*/agentstore/agents/:id", () => HttpResponse.json({ hitlConfig: GOOD_GATE })),
    http.get("*/administration/:env/deploy/:agentId/preflight", ({ params }) =>
      HttpResponse.json({
        agentId: params.agentId,
        version: 1,
        enforcement: "ENFORCE",
        checked: true,
        ready: !options.preflightIssues,
        grantIssues: options.preflightIssues
          ? [
              {
                tenantId: "default",
                keyName: KEY,
                reference: `\${vault:${KEY}}`,
                grantsAllAgents: false,
                allowedAgentCount: 2,
                allowedAgents: ["agent5", "agent7"],
              },
            ]
          : [],
      }),
    ),
    http.post("*/administration/:env/deploy/:agentId", ({ params }) => {
      spy.deploys += 1;
      return HttpResponse.json(
        options.deployBody ?? { status: "READY", agentId: params.agentId, version: 1, environment: "production" },
      );
    }),
    http.post("*/secretstore/secrets/:tenantId/:keyName/grant/agents/:agentId", ({ request, params }) => {
      const dryRun = new URL(request.url).searchParams.get("dryRun") === "true";
      if (!dryRun) spy.grants.push(`${params.keyName}->${params.agentId}`);
      return HttpResponse.json({
        reference: "x",
        tenantId: "default",
        keyName: params.keyName,
        dryRun,
        changed: true,
        allowedAgents: ["agent5", "agent7", params.agentId],
        previousAllowedAgents: ["agent5", "agent7"],
        grantsAllAgents: false,
        agentsLosingAccess: [],
      });
    }),
    http.put(VAR_URL, () => {
      spy.configWritten = true;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get(VAR_URL, () => HttpResponse.json({ error: "not found" }, { status: 404 })),
    http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
    http.delete("*/agentstore/agents/:id", ({ params }) => {
      spy.deleted.push(params.id as string);
      return new HttpResponse(null, { status: 200 });
    }),
    http.post("*/administration/operator/gate-dry-run", () =>
      HttpResponse.json({ policyPresent: true, gated: true, matchedPattern: "http.patch:*" }),
    ),
  );
}

function newSpy(): Spy {
  return { setupCalls: 0, setupDeployFlags: [], deleted: [], configWritten: false, grants: [], deploys: 0 };
}

function config(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return { ...defaultOperatorConfig("Body."), scope: "read_only", provider: "gemini", ...overrides };
}

describe("useActivateOperator — the vault grant", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  it("provisions WITHOUT deploying, so a grant can be settled first", async () => {
    const spy = newSpy();
    serve(spy);
    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    act(() => {
      result.current.mutate({ agentName: "EDDI Platform Operator", config: config(), apiKey: `\${vault:${KEY}}` });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy.setupDeployFlags).toEqual([false]);
    expect(spy.deploys).toBe(1);
    expect(spy.configWritten).toBe(true);
  });

  it("regression: a deploy that fails removes the agent it created — no leaked operator", async () => {
    // Used to throw from assertProvisioned BEFORE the rollback `try`, so the
    // created agent stayed behind, the config was never written (screen: "off")
    // and a retry made a second one.
    const spy = newSpy();
    serve(spy, {
      deployBody: {
        status: "ERROR",
        agentId: AGENT_ID,
        error: "Deployment failed. Check server logs for details.",
        failure: { code: "DEPLOYMENT_FAILED", message: "LLM provider rejected the model name" },
      },
    });
    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    act(() => {
      result.current.mutate({ agentName: "EDDI Platform Operator", config: config(), apiKey: "sk-test" });
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toMatch(/LLM provider rejected the model name/);
    expect(spy.deleted).toEqual([AGENT_ID]);
    expect(spy.configWritten).toBe(false);
  });

  it("resolves a restricted credential key inside activation, on the agent it just created", async () => {
    const spy = newSpy();
    serve(spy, { preflightIssues: true });
    const user = userEvent.setup();
    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    act(() => {
      result.current.mutate({ agentName: "EDDI Platform Operator", config: config(), apiKey: `\${vault:${KEY}}` });
    });

    await screen.findByTestId("grant-required-dialog");
    // Nothing deployed and nothing written while the admin decides.
    expect(spy.deploys).toBe(0);
    expect(spy.configWritten).toBe(false);
    await user.click(screen.getByTestId("grant-required-confirm"));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy.grants).toEqual([`${KEY}->${AGENT_ID}`]);
    expect(spy.setupCalls).toBe(1);
    expect(spy.deploys).toBe(1);
    expect(spy.configWritten).toBe(true);
    expect(result.current.data?.config.agentId).toBe(AGENT_ID);
  });

  it("a cancelled grant keeps the agent, and the retry reuses it instead of creating a second one", async () => {
    const spy = newSpy();
    serve(spy, { preflightIssues: true });
    const user = userEvent.setup();
    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    const params = { agentName: "EDDI Platform Operator", config: config(), apiKey: `\${vault:${KEY}}` };

    act(() => result.current.mutate(params));
    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-cancel"));
    await waitFor(() => expect(result.current.isError).toBe(true));

    const error = result.current.error;
    expect(error).toBeInstanceOf(OperatorGrantPendingError);
    const kept = (error as OperatorGrantPendingError).kept;
    expect(kept.agentId).toBe(AGENT_ID);
    // Kept: not removed, not deployed, not registered.
    expect(spy.deleted).toEqual([]);
    expect(spy.deploys).toBe(0);
    expect(spy.configWritten).toBe(false);

    act(() => result.current.mutate({ ...params, reuseAgent: kept }));
    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // ONE agent, ever.
    expect(spy.setupCalls).toBe(1);
    expect(spy.deleted).toEqual([]);
    expect(spy.grants).toEqual([`${KEY}->${AGENT_ID}`]);
    expect(result.current.data?.config.agentId).toBe(AGENT_ID);
  });

  it("does not reuse a kept agent built from different settings — it is removed and replaced", async () => {
    const spy = newSpy();
    serve(spy);
    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    act(() =>
      result.current.mutate({
        agentName: "EDDI Platform Operator",
        config: config(),
        apiKey: "sk-test",
        reuseAgent: { agentId: "op-old-kept", version: 1, fingerprint: "something else" },
      }),
    );
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy.deleted).toEqual(["op-old-kept"]);
    expect(spy.setupCalls).toBe(1);
  });
});

describe("useReactivateOperator - the vault grant", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  const disabled = () => config({ enabled: false, agentId: AGENT_ID, version: 1, environment: "production" });

  it("goes through the grant flow: asks, grants THE agent, deploys, and only then writes the config", async () => {
    const spy = newSpy();
    serve(spy, { preflightIssues: true });
    const user = userEvent.setup();
    const { result } = renderHook(() => useReactivateOperator(), { wrapper });
    act(() => result.current.mutate(disabled()));

    await screen.findByTestId("grant-required-dialog");
    // Nothing deployed and nothing written while the admin decides.
    expect(spy.deploys).toBe(0);
    expect(spy.configWritten).toBe(false);
    await user.click(screen.getByTestId("grant-required-confirm"));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy.grants).toEqual([`${KEY}->${AGENT_ID}`]);
    expect(spy.deploys).toBe(1);
    expect(spy.configWritten).toBe(true);
    expect(spy.setupCalls).toBe(0);
    expect(result.current.data?.enabled).toBe(true);
  });

  it("cancelling the grant deploys nothing, leaves the operator off and says why", async () => {
    const spy = newSpy();
    serve(spy, { preflightIssues: true });
    const user = userEvent.setup();
    const { result } = renderHook(() => useReactivateOperator(), { wrapper });
    act(() => result.current.mutate(disabled()));

    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-cancel"));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toMatch(/not enabled.*not granted/i);
    expect(spy.deploys).toBe(0);
    expect(spy.configWritten).toBe(false);
  });

  it("a deploy that fails for another reason is an error, not a silently enabled operator", async () => {
    const spy = newSpy();
    serve(spy, {
      deployBody: {
        status: "ERROR",
        agentId: AGENT_ID,
        failure: { code: "DEPLOYMENT_FAILED", message: "LLM provider rejected the model name" },
      },
    });
    const { result } = renderHook(() => useReactivateOperator(), { wrapper });
    act(() => result.current.mutate(disabled()));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toMatch(/LLM provider rejected the model name/);
    expect(spy.configWritten).toBe(false);
  });
});
