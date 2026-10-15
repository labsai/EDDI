import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { server } from "@/test/mocks/server";
import { renderPage } from "@/test/test-utils";
import { WorkforceWizard } from "@/pages/workforce/workforce-wizard";

/**
 * A new advisor is on no vault key's grant, so under
 * `eddi.vault.grant-enforcement=enforce` deploying it as part of its own setup
 * is refused. The wizard now creates every advisor undeployed and deploys the
 * team through the grant flow - the keys asked about ONCE for all advisors, and
 * no deploy (and no group) before the answer.
 */

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

const KEY = "google-gemini-key";

type User = ReturnType<typeof userEvent.setup>;

function renderWizard(template = "custom") {
  return renderPage(`/workforce/new?template=${template}`, <WorkforceWizard />, "/workforce/new");
}

const next = () => screen.getByRole("button", { name: /^next$/i });

/** Advance from the template step to the team step. */
async function gotoTeamStep(user: User) {
  await waitFor(() => expect(next()).toBeEnabled());
  await user.click(next());
  return await screen.findByLabelText(/workforce name/i);
}

/** Custom template: board name + both advisor names, nothing else. */
async function fillNamesOnly(user: User) {
  const boardName = await gotoTeamStep(user);
  await user.type(boardName, "Product Strategy Board");
  const nameFields = screen.getAllByLabelText(/advisor name/i);
  await user.type(nameFields[0]!, "Ana");
  await user.type(nameFields[1]!, "Bo");
}

/** Click Next (which fails and opens the cards), then fill both prompts. */
async function fillPrompts(user: User) {
  await user.click(next());
  const prompts = await screen.findAllByLabelText(/personality & expertise/i);
  await user.type(prompts[0]!, "You are a market analyst.");
  await user.type(prompts[1]!, "You are a risk officer.");
}

async function setDefaultKey(user: User, key = "sk-test") {
  await user.type(within(screen.getByTestId("apikey-defaults")).getByTestId("apikey-defaults-picker-input"), key);
}

/** Everything a custom team needs, ending on the Review step. */
async function completeCustomTeam(user: User) {
  await fillNamesOnly(user);
  await fillPrompts(user);
  await setDefaultKey(user);
  await user.click(next());
  return await screen.findByRole("button", { name: /create workforce/i });
}


let calls: string[];
let setupBodies: Array<Record<string, unknown>>;
let groupBodies: Array<{ members: Array<{ agentId: string }> }>;

function serve() {
  let n = 0;
  server.use(
    http.post("*/administration/agents/setup", async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      setupBodies.push(body);
      n += 1;
      calls.push(`setup ${n}`);
      return HttpResponse.json({
        action: "created",
        agentId: `new-agent-${n}`,
        agentName: String(body.name),
        provider: "anthropic",
        model: "m",
        deployed: false,
        resources: { agentLocation: `eddi://ai.labs.agent/agentstore/agents/new-agent-${n}?version=1` },
      });
    }),
    http.get("*/administration/:env/deploy/:agentId/preflight", ({ params }) => {
      calls.push(`preflight ${params.agentId}`);
      return HttpResponse.json({
        agentId: params.agentId,
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
            allowedAgentCount: 1,
            allowedAgents: ["agent5"],
          },
        ],
      });
    }),
    http.post("*/administration/:env/deploy/:agentId", ({ params }) => {
      calls.push(`deploy ${params.agentId}`);
      return HttpResponse.json({ status: "READY", agentId: params.agentId, version: 1, environment: "production" });
    }),
    http.post("*/secretstore/secrets/:tenantId/:keyName/grant/agents/:agentId", ({ request, params }) => {
      const dryRun = new URL(request.url).searchParams.get("dryRun") === "true";
      calls.push(`grant ${params.agentId}${dryRun ? " dry" : ""}`);
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
    http.post("*/groupstore/groups", async ({ request }) => {
      calls.push("group");
      groupBodies.push((await request.json()) as { members: Array<{ agentId: string }> });
      return new HttpResponse(null, {
        status: 201,
        headers: { Location: "eddi://ai.labs.group/groupstore/groups/grp-new?version=1" },
      });
    }),
  );
}

describe("Workforce wizard - vault grants for new advisors", () => {
  beforeEach(() => {
    calls = [];
    setupBodies = [];
    groupBodies = [];
    vi.clearAllMocks();
  });

  it("creates advisors undeployed, asks about the key ONCE, grants, deploys, then saves the group", async () => {
    serve();
    const user = userEvent.setup();
    renderWizard();
    await user.click(await completeCustomTeam(user));

    const dialog = await screen.findByTestId("grant-required-dialog");
    expect(screen.getAllByTestId(`grant-issue-${KEY}`)).toHaveLength(1);
    expect(dialog).toHaveTextContent("Ana");
    expect(dialog).toHaveTextContent("Bo");
    // No deploy and no group while the question is open.
    expect(calls.some((c) => c.startsWith("deploy"))).toBe(false);
    expect(calls).not.toContain("group");

    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(groupBodies).toHaveLength(1));

    expect(setupBodies).toHaveLength(2);
    for (const body of setupBodies) expect(body.deploy).toBe(false);
    const firstDeploy = calls.findIndex((c) => c.startsWith("deploy"));
    const lastGrant = calls.map((c) => c.startsWith("grant")).lastIndexOf(true);
    expect(firstDeploy).toBeGreaterThan(lastGrant);
    expect(calls.filter((c) => c.startsWith("grant") && !c.endsWith("dry")).sort()).toEqual([
      "grant new-agent-1",
      "grant new-agent-2",
    ]);
    expect(calls.filter((c) => c.startsWith("deploy"))).toEqual(["deploy new-agent-1", "deploy new-agent-2"]);
    expect(calls.indexOf("group")).toBeGreaterThan(calls.lastIndexOf("deploy new-agent-2"));
    expect(groupBodies[0]!.members.map((m) => m.agentId)).toEqual(["new-agent-1", "new-agent-2"]);
  });

  it("cancel deploys nothing and saves no group; Try Again deploys the advisors it already made", async () => {
    serve();
    const user = userEvent.setup();
    renderWizard();
    await user.click(await completeCustomTeam(user));

    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-cancel"));

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    expect(String(vi.mocked(toast.error).mock.calls[0]![0])).toMatch(/not deployed/i);
    expect(calls.some((c) => c.startsWith("deploy") || c.startsWith("grant"))).toBe(false);
    expect(calls).not.toContain("group");
    expect(toast.success).not.toHaveBeenCalled();

    await user.click(await screen.findByRole("button", { name: /try again/i }));
    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(groupBodies).toHaveLength(1));
    // Not created a second time, but deployed this time.
    expect(setupBodies).toHaveLength(2);
    expect(calls.filter((c) => c.startsWith("deploy"))).toEqual(["deploy new-agent-1", "deploy new-agent-2"]);
  });
});
