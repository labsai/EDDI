import { describe, it, expect, vi, beforeEach } from "vitest";
import { useState } from "react";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { render } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { createTestQueryClient, renderWithProviders, userEvent } from "@/test/test-utils";
import { GrantRequiredDialogHost } from "@/components/secrets/grant-required-dialog";
import { server } from "@/test/mocks/server";
import { useDeployWithGrants, type DeployWithGrantsOutcome } from "@/hooks/use-deploy-with-grants";
import { AuthContext, GUEST_CONTEXT } from "@/components/auth/auth-context";
import type { DeploymentFailure, DeploymentPreflight } from "@/lib/api/agents";

/**
 * Grant, then deploy. The vault mock is stateful and seeded with
 * `google-gemini-key` granted to agent5 and agent7 only; the agent under test
 * (`agent-new`) is on no grant, which is the state every new agent is in.
 */

const AGENT = "agent-new";
const KEY = "google-gemini-key";

function preflight(overrides: Partial<DeploymentPreflight> = {}): DeploymentPreflight {
  return {
    agentId: AGENT,
    version: 1,
    enforcement: "ENFORCE",
    checked: true,
    ready: false,
    grantIssues: [
      {
        tenantId: "default",
        keyName: KEY,
        reference: `\${vault:${KEY}}`,
        grantsAllAgents: false,
        allowedAgentCount: 2,
        allowedAgents: ["agent5", "agent7"],
      },
    ],
    ...overrides,
  };
}

const GRANT_FAILURE: DeploymentFailure = {
  code: "VAULT_GRANT_MISSING",
  message: `Agent '${AGENT}' v1 uses vault secret(s) it is not granted: default/${KEY}.`,
  secrets: [{ tenantId: "default", keyName: KEY, reference: `\${vault:${KEY}}` }],
  fix: {
    addAgentId: AGENT,
    endpoints: [`POST /secretstore/secrets/default/${KEY}/grant/agents/${AGENT}`],
    dryRunFirst: true,
  },
};

/** Records the order of the calls that matter. */
let calls: string[];

function serve(options: { preflight?: DeploymentPreflight | null; deployBodies?: Record<string, unknown>[] } = {}) {
  const bodies = [...(options.deployBodies ?? [{ status: "READY", agentId: AGENT, version: 1, environment: "production" }])];
  server.use(
    http.get("*/administration/:env/deploy/:agentId/preflight", () => {
      calls.push("preflight");
      return options.preflight === null
        ? HttpResponse.json({ error: "not found" }, { status: 404 })
        : HttpResponse.json(options.preflight ?? preflight());
    }),
    http.post("*/administration/:env/deploy/:agentId", ({ request }) => {
      const url = new URL(request.url);
      calls.push(`deploy wait=${url.searchParams.get("waitForCompletion")}`);
      return HttpResponse.json((bodies.length > 1 ? bodies.shift() : bodies[0]) as Record<string, unknown>);
    }),
    http.post("*/secretstore/secrets/:tenantId/:keyName/grant/agents/:agentId", async ({ request, params }) => {
      const dryRun = new URL(request.url).searchParams.get("dryRun") === "true";
      calls.push(`grant ${params.keyName} ${params.agentId}${dryRun ? " dry" : ""}`);
      return HttpResponse.json({
        reference: `\${vault:${params.keyName}}`,
        tenantId: params.tenantId,
        keyName: params.keyName,
        dryRun,
        changed: true,
        allowedAgents: ["agent5", "agent7", params.agentId],
        previousAllowedAgents: ["agent5", "agent7"],
        grantsAllAgents: false,
        agentsLosingAccess: [],
      });
    }),
  );
}

function Harness() {
  const { deploy } = useDeployWithGrants();
  const [outcome, setOutcome] = useState<DeployWithGrantsOutcome | null>(null);
  return (
    <div>
      <button
        data-testid="go"
        onClick={() => void deploy({ agentId: AGENT, agentName: "New Agent", version: 1 }).then(setOutcome)}
      />
      <span data-testid="outcome">{outcome ? outcome.kind : "pending"}</span>
      <span data-testid="message">{outcome?.kind === "failed" ? (outcome.message ?? "") : ""}</span>
    </div>
  );
}

const NON_ADMIN = { ...GUEST_CONTEXT, method: "keycloak" as const, roles: ["eddi-editor"] };

describe("useDeployWithGrants", () => {
  beforeEach(() => {
    calls = [];
  });

  it("preflight → dialog → confirm → dry run → grant → deploy", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));

    const dialog = await screen.findByTestId("grant-required-dialog");
    expect(dialog).toHaveTextContent(KEY);
    // Nothing deploys while the question is open.
    expect(calls).toEqual(["preflight"]);

    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("deployed"));
    expect(calls).toEqual([
      "preflight",
      `grant ${KEY} ${AGENT} dry`,
      `grant ${KEY} ${AGENT}`,
      "deploy wait=true",
    ]);
    expect(screen.queryByTestId("grant-required-dialog")).not.toBeInTheDocument();
  });

  it("deploys without asking when the preflight finds nothing", async () => {
    serve({ preflight: preflight({ ready: true, grantIssues: [] }) });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("deployed"));
    expect(calls).toEqual(["preflight", "deploy wait=true"]);
    expect(screen.queryByTestId("grant-required-dialog")).not.toBeInTheDocument();
  });

  it("cancel deploys nothing", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-cancel"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("cancelled"));
    expect(calls).toEqual(["preflight"]);
  });

  it("a refused deploy the preflight did not predict opens the SAME dialog from the failure", async () => {
    // The preflight could not run (an older backend, or checked:false), so the
    // first anyone hears of the grant is the deploy's own refusal.
    serve({
      preflight: null,
      deployBodies: [
        { status: "ERROR", agentId: AGENT, version: 1, environment: "production", error: GRANT_FAILURE.message, failure: GRANT_FAILURE },
        { status: "READY", agentId: AGENT, version: 1, environment: "production" },
      ],
    });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));

    await screen.findByTestId("grant-required-dialog");
    expect(screen.getByTestId("grant-required-failure")).toHaveTextContent(GRANT_FAILURE.message);
    expect(screen.getByTestId(`grant-issue-${KEY}`)).toBeInTheDocument();

    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("deployed"));
    expect(calls).toEqual([
      "preflight",
      "deploy wait=true",
      `grant ${KEY} ${AGENT} dry`,
      `grant ${KEY} ${AGENT}`,
      "deploy wait=true",
    ]);
  });

  it("reports a non-grant failure with the backend's reason", async () => {
    serve({
      preflight: preflight({ ready: true, grantIssues: [] }),
      deployBodies: [
        {
          status: "ERROR",
          agentId: AGENT,
          error: "Deployment failed. Check server logs for details.",
          failure: { code: "DEPLOYMENT_FAILED", message: "Workflow wf-1 v2 not found" },
        },
      ],
    });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("failed"));
    expect(screen.getByTestId("message")).toHaveTextContent("Workflow wf-1 v2 not found");
    expect(screen.queryByTestId("grant-required-dialog")).not.toBeInTheDocument();
  });

  it("a non-admin gets the request to copy, and no button that grants", async () => {
    serve({ preflight: preflight({ grantIssues: [{ ...preflight().grantIssues[0]!, allowedAgents: null }] }) });
    const user = userEvent.setup();
    // After setup(): user-event installs a clipboard stub of its own.
    const writeText = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue(undefined);
    // The dialog host must sit INSIDE the auth context, as it does in the app
    // (main.tsx mounts it under AuthProvider) — so not through renderWithProviders,
    // whose host is outside any provider a test adds.
    render(
      <MemoryRouter>
        <QueryClientProvider client={createTestQueryClient()}>
          <AuthContext.Provider value={NON_ADMIN}>
            <Harness />
            <GrantRequiredDialogHost />
          </AuthContext.Provider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await user.click(screen.getByTestId("go"));

    await screen.findByTestId("grant-required-non-admin");
    expect(screen.queryByTestId("grant-required-confirm")).not.toBeInTheDocument();
    // The count, not the ids — an editor is not told who else holds the key.
    expect(screen.getByTestId(`grant-issue-${KEY}`)).toHaveTextContent("2");

    await user.click(screen.getByTestId("grant-required-copy"));
    expect(writeText).toHaveBeenCalledTimes(1);
    const copied = writeText.mock.calls[0]![0] as string;
    const lines = copied.split("\n").filter((l) => l.startsWith("curl"));
    // Dry run first, then the change.
    expect(lines[0]).toContain(`/secretstore/secrets/default/${KEY}/grant/agents/${AGENT}?dryRun=true`);
    expect(lines[1]).toContain(`/secretstore/secrets/default/${KEY}/grant/agents/${AGENT}"`);
    expect(copied).not.toMatch(/Bearer ey/);

    await user.click(screen.getByTestId("grant-required-cancel"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("cancelled"));
    expect(calls).toEqual(["preflight"]);
  });

  it("in WARN mode offers Deploy anyway, which deploys without granting", async () => {
    serve({ preflight: preflight({ enforcement: "WARN", ready: true }) });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await user.click(await screen.findByTestId("grant-required-deploy-anyway"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("deployed"));
    expect(calls).toEqual(["preflight", "deploy wait=true"]);
  });

  it("offers Deploy anyway only in WARN mode", async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await screen.findByTestId("grant-required-dialog");
    expect(screen.queryByTestId("grant-required-deploy-anyway")).not.toBeInTheDocument();
  });

  it("'allow every agent' is a separate, warned choice that widens through the grant PUT", async () => {
    serve();
    let putBody: unknown = null;
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName/grant", async ({ request, params }) => {
        putBody = await request.json();
        calls.push(`put ${params.keyName}`);
        return HttpResponse.json({
          reference: "x",
          tenantId: "default",
          keyName: params.keyName,
          dryRun: false,
          allowedAgents: ["*"],
          previousAllowedAgents: ["agent5", "agent7"],
          grantsAllAgents: true,
          agentsLosingAccess: [],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await screen.findByTestId("grant-required-dialog");
    expect(screen.queryByTestId("grant-required-allow-all-warning")).not.toBeInTheDocument();
    await user.click(screen.getByTestId("grant-required-allow-all"));
    expect(screen.getByTestId("grant-required-allow-all-warning")).toBeInTheDocument();
    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(screen.getByTestId("outcome")).toHaveTextContent("deployed"));
    expect(putBody).toEqual({ allowedAgents: ["*"] });
    expect(calls).toEqual(["preflight", `put ${KEY}`, "deploy wait=true"]);
  });
});
