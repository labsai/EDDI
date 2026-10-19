import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { DeploymentPreflight } from "@/lib/api/agents";

/**
 * Batch deploys run a few at a time instead of one after another, while the
 * grant dialog — which shows one request and cancels any open one — is still
 * asked one question at a time.
 */
const api = vi.hoisted(() => ({
  deployAgentAndWait: vi.fn(),
  preflightDeploy: vi.fn(),
}));
vi.mock("@/lib/api/agents", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/agents")>()),
  deployAgentAndWait: api.deployAgentAndWait,
  preflightDeploy: api.preflightDeploy,
}));

import {
  DEPLOY_MANY_CONCURRENCY,
  runDeployManyWithGrants,
  serializeGrantDecisions,
  useGrantDialogStore,
} from "@/hooks/use-deploy-with-grants";

const NO_ISSUES = { checked: true, enforcement: "OFF", grantIssues: [] } as unknown as DeploymentPreflight;
const targets = Array.from({ length: 5 }, (_, i) => ({ agentId: `agent-${i}`, version: 1 }));

let unregister: () => void;
beforeEach(() => {
  api.preflightDeploy.mockResolvedValue(NO_ISSUES);
  unregister = useGrantDialogStore.getState().registerHost();
});
afterEach(() => {
  unregister();
  vi.clearAllMocks();
});

describe("runDeployManyWithGrants", () => {
  it(`deploys ${DEPLOY_MANY_CONCURRENCY} at a time, not one after another`, async () => {
    let running = 0;
    let peak = 0;
    api.deployAgentAndWait.mockImplementation(async (environment: string, agentId: string, version: number) => {
      running += 1;
      peak = Math.max(peak, running);
      await new Promise((r) => setTimeout(r, 10));
      running -= 1;
      return { status: "READY", agentId, version, environment };
    });

    const results = await runDeployManyWithGrants({ targets });

    expect(peak).toBe(DEPLOY_MANY_CONCURRENCY);
    expect(results.map((r) => [r.target.agentId, r.outcome.kind])).toEqual(
      targets.map((t) => [t.agentId, "deployed"]),
    );
  });
});

describe("runDeployManyWithGrants and the grant dialog", () => {
  it("asks about each refused agent in turn instead of cancelling the open question", async () => {
    const refused = { code: "VAULT_GRANT_MISSING", secrets: [] };
    const granted = new Set<string>();
    api.deployAgentAndWait.mockImplementation(async (environment: string, agentId: string, version: number) =>
      granted.has(agentId)
        ? { status: "READY", agentId, version, environment }
        : { status: "ERROR", agentId, version, environment, failure: refused },
    );

    const run = runDeployManyWithGrants({ targets: targets.slice(0, 2) });
    for (const agentId of ["agent-0", "agent-1"]) {
      await vi.waitFor(() => expect(useGrantDialogStore.getState().pending?.agentId).toBe(agentId));
      granted.add(agentId);
      useGrantDialogStore.getState().pending!.resolve("granted");
    }

    // Both answered "granted", so both redeployed; neither was cancelled by
    // the other's question opening on top of it.
    expect((await run).map((r) => r.outcome.kind)).toEqual(["deployed", "deployed"]);
  });
});

describe("serializeGrantDecisions", () => {
  it("opens the next question only once the previous one is answered", async () => {
    const ask = serializeGrantDecisions();
    const request = (agentId: string) =>
      ({ agentId, version: 1, environment: "production", enforcement: "UNKNOWN", issues: [], source: "failure" }) as never;

    const first = ask(request("a"));
    const second = ask(request("b"));
    await vi.waitFor(() => expect(useGrantDialogStore.getState().pending?.agentId).toBe("a"));

    useGrantDialogStore.getState().pending!.resolve("granted");
    // The first answer stands — the second question did not cancel it.
    await expect(first).resolves.toBe("granted");
    await vi.waitFor(() => expect(useGrantDialogStore.getState().pending?.agentId).toBe("b"));

    useGrantDialogStore.getState().pending!.resolve("deploy-anyway");
    await expect(second).resolves.toBe("deploy-anyway");
  });
});
