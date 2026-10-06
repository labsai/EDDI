import { describe, it, expect, beforeEach } from "vitest";
import { useState } from "react";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import {
  mergeGrantIssues,
  runDeployManyWithGrants,
  useDeployWithGrants,
  type DeployManyResult,
  type DeployTarget,
} from "@/hooks/use-deploy-with-grants";
import type { DeploymentFailure, DeploymentPreflight } from "@/lib/api/agents";

/**
 * Several agents created together are granted in ONE question: each restricted
 * key is listed once with every agent that needs it, one confirmation adds them
 * all, cancel deploys none, and the unpredicted refusal still gets its single
 * second chance, per agent.
 */

const KEY = "google-gemini-key";
const OTHER_KEY = "openai-key";
const A = "agent-a";
const B = "agent-b";
const C = "agent-c";

const TARGETS: DeployTarget[] = [
  { agentId: A, version: 1, agentName: "Ana" },
  { agentId: B, version: 1, agentName: "Bo" },
  { agentId: C, version: 1, agentName: "Cy" },
];

function issue(keyName: string) {
  return {
    tenantId: "default",
    keyName,
    reference: `\${vault:${keyName}}`,
    grantsAllAgents: false,
    allowedAgentCount: 1,
    allowedAgents: ["agent5"],
  };
}

type Preflights = Record<string, Partial<DeploymentPreflight>>;

let calls: string[];

function serve(options: { preflights: Preflights; deploys?: Record<string, Record<string, unknown>[]> }) {
  const deploys = options.deploys ?? {};
  server.use(
    http.get("*/administration/:env/deploy/:agentId/preflight", ({ params }) => {
      const id = params.agentId as string;
      calls.push(`preflight ${id}`);
      const mine = options.preflights[id] ?? {};
      return HttpResponse.json({
        agentId: id,
        version: 1,
        enforcement: "ENFORCE",
        checked: true,
        ready: (mine.grantIssues ?? []).length === 0,
        grantIssues: [],
        ...mine,
      });
    }),
    http.post("*/administration/:env/deploy/:agentId", ({ params }) => {
      const id = params.agentId as string;
      calls.push(`deploy ${id}`);
      const queue = deploys[id];
      const body = (queue && queue.length > 1 ? queue.shift() : queue?.[0]) ?? {
        status: "READY",
        agentId: id,
        version: 1,
        environment: "production",
      };
      return HttpResponse.json(body);
    }),
    http.post("*/secretstore/secrets/:tenantId/:keyName/grant/agents/:agentId", ({ request, params }) => {
      const dryRun = new URL(request.url).searchParams.get("dryRun") === "true";
      calls.push(`grant ${params.keyName} ${params.agentId}${dryRun ? " dry" : ""}`);
      return HttpResponse.json({
        reference: `\${vault:${params.keyName}}`,
        tenantId: params.tenantId,
        keyName: params.keyName,
        dryRun,
        changed: true,
        allowedAgents: ["agent5", params.agentId],
        previousAllowedAgents: ["agent5"],
        grantsAllAgents: false,
        agentsLosingAccess: [],
      });
    }),
  );
}

function Harness({ targets = TARGETS }: { targets?: DeployTarget[] }) {
  const { deployMany } = useDeployWithGrants();
  const [results, setResults] = useState<DeployManyResult[] | null>(null);
  return (
    <div>
      <button data-testid="go" onClick={() => void deployMany({ targets }).then(setResults)} />
      <span data-testid="outcomes">
        {results ? results.map((r) => `${r.target.agentId}:${r.outcome.kind}`).join(",") : "pending"}
      </span>
    </div>
  );
}

const needsKey = (...keys: string[]) => ({ grantIssues: keys.map(issue) });

describe("runDeployManyWithGrants", () => {
  beforeEach(() => {
    calls = [];
  });

  it("asks ONCE for the shared key, appends every agent, and only then deploys", async () => {
    serve({ preflights: { [A]: needsKey(KEY), [B]: needsKey(KEY), [C]: needsKey(KEY) } });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));

    const dialog = await screen.findByTestId("grant-required-dialog");
    // One issue for the key, naming all three agents - not three dialogs.
    expect(screen.getAllByTestId(`grant-issue-${KEY}`)).toHaveLength(1);
    expect(dialog).toHaveTextContent("Ana");
    expect(dialog).toHaveTextContent("Bo");
    expect(dialog).toHaveTextContent("Cy");
    // Nothing deploys while the question is open.
    expect(calls.filter((c) => c.startsWith("deploy"))).toEqual([]);

    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(screen.getByTestId("outcomes")).toHaveTextContent(`${A}:deployed,${B}:deployed,${C}:deployed`));

    const grants = calls.filter((c) => c.startsWith("grant") && !c.endsWith("dry"));
    expect(grants.sort()).toEqual([`grant ${KEY} ${A}`, `grant ${KEY} ${B}`, `grant ${KEY} ${C}`]);
    // Every grant landed before the first deploy.
    const firstDeploy = calls.findIndex((c) => c.startsWith("deploy"));
    const lastGrant = calls.map((c) => c.startsWith("grant")).lastIndexOf(true);
    expect(lastGrant).toBeLessThan(firstDeploy);
    expect(calls.filter((c) => c.startsWith("deploy"))).toEqual([`deploy ${A}`, `deploy ${B}`, `deploy ${C}`]);
  });

  it("lists each key once, with only the agents that need it", async () => {
    serve({ preflights: { [A]: needsKey(KEY), [B]: needsKey(KEY, OTHER_KEY), [C]: {} } });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await screen.findByTestId("grant-required-dialog");

    expect(screen.getAllByTestId(`grant-issue-${KEY}`)).toHaveLength(1);
    expect(screen.getAllByTestId(`grant-issue-${OTHER_KEY}`)).toHaveLength(1);
    expect(screen.getByTestId(`grant-issue-${KEY}`)).toHaveTextContent("Ana, Bo");
    expect(screen.getByTestId(`grant-issue-${OTHER_KEY}`)).toHaveTextContent("Bo");
    expect(screen.getByTestId(`grant-issue-${OTHER_KEY}`)).not.toHaveTextContent("Ana");

    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(screen.getByTestId("outcomes")).toHaveTextContent(`${C}:deployed`));
    expect(calls.filter((c) => c.startsWith("grant") && !c.endsWith("dry")).sort()).toEqual([
      `grant ${OTHER_KEY} ${B}`,
      `grant ${KEY} ${A}`,
      `grant ${KEY} ${B}`,
    ].sort());
  });

  it("cancel deploys nothing - not even the agents that needed no grant", async () => {
    serve({ preflights: { [A]: needsKey(KEY), [B]: needsKey(KEY), [C]: {} } });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-cancel"));

    await waitFor(() =>
      expect(screen.getByTestId("outcomes")).toHaveTextContent(`${A}:cancelled,${B}:cancelled,${C}:cancelled`),
    );
    expect(calls.filter((c) => c.startsWith("deploy") || c.startsWith("grant"))).toEqual([]);
  });

  it("deploys nothing more once the caller aborts", async () => {
    serve({ preflights: {} });
    const controller = new AbortController();
    controller.abort();
    const results = await runDeployManyWithGrants({ targets: TARGETS, signal: controller.signal });
    expect(results.map((r) => r.outcome.kind)).toEqual(["cancelled", "cancelled", "cancelled"]);
    expect(calls.filter((c) => c.startsWith("deploy"))).toEqual([]);
  });

  it("deploys without asking when no agent has a grant issue", async () => {
    serve({ preflights: {} });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await waitFor(() => expect(screen.getByTestId("outcomes")).toHaveTextContent(`${C}:deployed`));
    expect(screen.queryByTestId("grant-required-dialog")).not.toBeInTheDocument();
  });

  it("a single agent needing a grant gets the ordinary single-agent dialog", async () => {
    serve({ preflights: { [A]: needsKey(KEY), [B]: {}, [C]: {} } });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await screen.findByTestId("grant-required-dialog");
    expect(screen.getByTestId(`grant-issue-${KEY}`)).toHaveTextContent("Ana");
    expect(screen.getByTestId(`grant-issue-${KEY}`)).not.toHaveTextContent("Bo");
    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(screen.getByTestId("outcomes")).toHaveTextContent(`${C}:deployed`));
    expect(calls.filter((c) => c.startsWith("grant") && !c.endsWith("dry"))).toEqual([`grant ${KEY} ${A}`]);
  });

  it("an unexpected refusal gets its one second chance, for that agent only", async () => {
    const failure: DeploymentFailure = {
      code: "VAULT_GRANT_MISSING",
      message: `Agent '${B}' v1 uses vault secret(s) it is not granted: default/${KEY}.`,
      secrets: [{ tenantId: "default", keyName: KEY, reference: `\${vault:${KEY}}` }],
    };
    serve({
      preflights: {},
      deploys: {
        [B]: [
          { status: "ERROR", agentId: B, version: 1, environment: "production", failure },
          { status: "READY", agentId: B, version: 1, environment: "production" },
        ],
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));

    await screen.findByTestId("grant-required-dialog");
    expect(screen.getByTestId("grant-required-failure")).toHaveTextContent(failure.message);
    await user.click(screen.getByTestId("grant-required-confirm"));

    await waitFor(() => expect(screen.getByTestId("outcomes")).toHaveTextContent(`${A}:deployed,${B}:deployed,${C}:deployed`));
    expect(calls.filter((c) => c === `deploy ${B}`)).toHaveLength(2);
    expect(calls.filter((c) => c === `deploy ${A}`)).toHaveLength(1);
    expect(calls.filter((c) => c.startsWith("grant") && !c.endsWith("dry"))).toEqual([`grant ${KEY} ${B}`]);
  });

  it("reports a failed deploy per agent without hiding the others", async () => {
    serve({
      preflights: {},
      deploys: {
        [B]: [
          {
            status: "ERROR",
            agentId: B,
            version: 1,
            environment: "production",
            failure: { code: "DEPLOYMENT_FAILED", message: "Workflow wf-1 not found" },
          },
        ],
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    await user.click(screen.getByTestId("go"));
    await waitFor(() => expect(screen.getByTestId("outcomes")).toHaveTextContent(`${A}:deployed,${B}:failed,${C}:deployed`));
  });
});

describe("mergeGrantIssues", () => {
  it("merges by tenant and key, falling back to the reference", () => {
    const merged = mergeGrantIssues([
      { agentId: "a", issues: [{ tenantId: "t", keyName: "k", reference: "${vault:k}" }, { reference: "plain" }] },
      { agentId: "b", issues: [{ tenantId: "t", keyName: "k", reference: "${vault:k}" }, { reference: "plain" }] },
      { agentId: "b", issues: [{ tenantId: "t", keyName: "k", reference: "${vault:k}" }] },
    ]);
    expect(merged).toHaveLength(2);
    expect(merged[0]!.agentIds).toEqual(["a", "b"]);
    expect(merged[1]!.agentIds).toEqual(["a", "b"]);
  });
});
