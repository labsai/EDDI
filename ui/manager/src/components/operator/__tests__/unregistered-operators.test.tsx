import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { UnregisteredOperators } from "@/components/operator/unregistered-operators";
import { OPERATOR_VARIABLE_KEY, type OperatorConfig } from "@/lib/api/operator";
import { OPERATOR_DESCRIPTOR_DESCRIPTION } from "@/lib/api/operator-marker";

/**
 * Operator agents the config does not point at: found, adopted, removed.
 *
 * - `op-kept` carries the descriptor marker (an activation kept it after a
 *   refused grant) and is in ERROR for that grant.
 * - `op-legacy` has no marker but is named like an operator, has the approval
 *   gate, and its tools target this instance — a pre-marker operator.
 * - `ops-helper` is named like one, but its tools target somewhere else.
 */

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;
const SELF = "http://127.0.0.1:7070";

const GOOD_GATE = {
  toolApprovals: {
    requireApproval: ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"],
    exempt: ["http.get:*"],
    timeoutPolicy: "WAIT_INDEFINITELY",
  },
};

const DESCRIPTORS = [
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/op-kept?version=1",
    name: "EDDI Platform Operator",
    description: OPERATOR_DESCRIPTOR_DESCRIPTION,
    createdOn: 0,
    lastModifiedOn: 0,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/op-legacy?version=2",
    name: "Old Platform Operator",
    description: "",
    createdOn: 0,
    lastModifiedOn: 0,
  },
  {
    resource: "eddi://ai.labs.agent/agentstore/agents/ops-helper?version=1",
    name: "Operator FAQ bot",
    description: "",
    createdOn: 0,
    lastModifiedOn: 0,
  },
];

const GRANT_FAILURE = {
  code: "VAULT_GRANT_MISSING",
  message: "Agent 'op-kept' v1 uses vault secret(s) it is not granted: default/op-key.",
  secrets: [{ tenantId: "default", keyName: "op-key", reference: "${vault:op-key}" }],
};

let spy: { configBody: unknown; deleted: string[]; descriptorFilter: string | null };

function serve() {
  server.use(
    http.get("*/agentstore/agents/descriptors", ({ request }) => {
      spy.descriptorFilter = new URL(request.url).searchParams.get("filter");
      return HttpResponse.json(DESCRIPTORS);
    }),
    http.get("*/administration/operator/self-url", () => HttpResponse.json({ baseUrl: SELF, source: "loopback" })),
    http.get("*/agentstore/agents/:id/currentversion", ({ params }) =>
      HttpResponse.json(params.id === "op-legacy" ? 2 : 1),
    ),
    http.get("*/agentstore/agents/:id", ({ params }) =>
      HttpResponse.json({
        workflows: [`eddi://ai.labs.workflow/workflowstore/workflows/wf-${params.id}?version=1`],
        // The FAQ bot is gated too — it is the target that tells it apart.
        hitlConfig: GOOD_GATE,
      }),
    ),
    http.get("*/workflowstore/workflows/:id", ({ params }) =>
      HttpResponse.json({
        workflowSteps: [
          { type: "eddi://ai.labs.llm", config: { uri: `eddi://ai.labs.llm/llmstore/llms/llm-${params.id}?version=1` } },
          {
            type: "eddi://ai.labs.httpcalls",
            config: { uri: `eddi://ai.labs.apicalls/apicallstore/apicalls/api-${params.id}?version=1` },
          },
        ],
      }),
    ),
    http.get("*/apicallstore/apicalls/:id", ({ params }) =>
      HttpResponse.json({
        targetServerUrl: String(params.id).includes("ops-helper") ? "https://faq.example.com" : `${SELF}/`,
        httpCalls: [
          { request: { method: "get" } },
          { request: { method: "post", headers: { Authorization: "Bearer ${caller:token}" } } },
        ],
      }),
    ),
    http.get("*/llmstore/llms/:id", () =>
      HttpResponse.json({
        tasks: [{ type: "anthropic", parameters: { model: "claude-sonnet-5-5", apiKey: "${vault:op-key}" } }],
      }),
    ),
    http.get("*/administration/:env/deploymentstatus/:agentId", ({ params }) =>
      HttpResponse.json(
        params.agentId === "op-kept" ? { status: "ERROR", failure: GRANT_FAILURE } : { status: "READY" },
      ),
    ),
    http.put(VAR_URL, async ({ request }) => {
      const body = (await request.json()) as { value: string };
      spy.configBody = JSON.parse(body.value);
      return new HttpResponse(null, { status: 204 });
    }),
    http.post("*/administration/:env/undeploy/:agentId", () => new HttpResponse(null, { status: 200 })),
    http.delete("*/agentstore/agents/:id", ({ params }) => {
      spy.deleted.push(params.id as string);
      return new HttpResponse(null, { status: 200 });
    }),
  );
}

describe("UnregisteredOperators", () => {
  beforeEach(() => {
    spy = { configBody: null, deleted: [], descriptorFilter: null };
    vi.spyOn(console, "warn").mockImplementation(() => {});
    serve();
  });

  it("lists the marked operator and the pre-marker one, and not an agent that merely shares the name", async () => {
    renderWithProviders(<UnregisteredOperators config={null} registeredHealthy={false} />);
    expect(await screen.findByTestId("operator-unregistered-op-kept")).toBeInTheDocument();
    expect(await screen.findByTestId("operator-unregistered-op-legacy")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-unregistered-ops-helper")).not.toBeInTheDocument();
    // One cheap listing that catches both a default name and the marker.
    expect(spy.descriptorFilter).toBe("perator");
    // The refused grant is shown with its reason.
    expect(screen.getByTestId("operator-unregistered-failure-op-kept")).toHaveTextContent("not granted");
    expect(screen.getByTestId("operator-unregistered-deploy-op-kept")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-unregistered-deploy-op-legacy")).not.toBeInTheDocument();
  });

  it("does not list the agent the config already points at", async () => {
    const config = { agentId: "op-legacy", version: 2, environment: "production", enabled: true } as OperatorConfig;
    renderWithProviders(<UnregisteredOperators config={config} registeredHealthy />);
    expect(await screen.findByTestId("operator-unregistered-op-kept")).toBeInTheDocument();
    expect(screen.queryByTestId("operator-unregistered-op-legacy")).not.toBeInTheDocument();
    // A healthy registered operator is not offered for replacement.
    expect(screen.queryByTestId("operator-unregistered-adopt-op-kept")).not.toBeInTheDocument();
  });

  it("Adopt writes platform.operator from the agent's own configuration", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UnregisteredOperators config={null} registeredHealthy={false} />);
    await user.click(await screen.findByTestId("operator-unregistered-adopt-op-legacy"));
    await waitFor(() => expect(spy.configBody).not.toBeNull());
    expect(spy.configBody).toMatchObject({
      agentId: "op-legacy",
      version: 2,
      enabled: true,
      provider: "anthropic",
      model: "claude-sonnet-5-5",
      credentialKey: "op-key",
      apiBaseUrl: SELF,
      scope: "read_write",
      authMode: "caller-identity",
    });
  });

  it("Remove deletes the agent after confirmation", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UnregisteredOperators config={null} registeredHealthy={false} />);
    const row = await screen.findByTestId("operator-unregistered-op-kept");
    await user.click(within(row).getByTestId("operator-unregistered-remove-op-kept"));
    expect(spy.deleted).toEqual([]);
    await user.click(await screen.findByRole("button", { name: /^Remove$/ }));
    await waitFor(() => expect(spy.deleted).toEqual(["op-kept"]));
  });
});
