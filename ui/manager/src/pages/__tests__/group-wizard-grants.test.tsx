import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { GroupWizardPage } from "@/pages/group-wizard";

/**
 * Under `eddi.vault.grant-enforcement=enforce` a brand-new agent that uses a
 * restricted vault key is refused at deploy - it is on no grant yet. The group
 * wizard used to create its members with `deploy: true`, so the refusal came
 * back inside the setup call with nothing the wizard could do about it.
 *
 * Members are now created undeployed and deployed together through the grant
 * flow: one question per key for the whole team, and no deploy before it is
 * answered.
 */

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}));

const KEY = "google-gemini-key";
type User = ReturnType<typeof userEvent.setup>;

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
        headers: { Location: "/groupstore/groups/new-grp?version=1" },
      });
    }),
  );
}

async function reachReview(user: User) {
  renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
  await user.click(screen.getByTestId("template-blank"));
  await user.type(screen.getByTestId("gw-name"), "Grant Group");
  await user.click(screen.getByTestId("group-wizard-next"));
  await user.click(screen.getByTestId("gw-add-member"));
  await user.click(screen.getByTestId("gw-add-member"));
  await user.type(await screen.findByTestId("member-name-0"), "Alice");
  await user.type(screen.getByTestId("member-name-1"), "Bob");
  await user.click(screen.getByTestId("group-wizard-next"));
  return await screen.findByTestId("group-wizard-create");
}

describe("GroupWizardPage - vault grants for new members", () => {
  beforeEach(() => {
    calls = [];
    setupBodies = [];
    groupBodies = [];
    vi.clearAllMocks();
  });

  it("creates members undeployed, asks about the key ONCE, grants, deploys, then saves the group", async () => {
    serve();
    const user = userEvent.setup();
    await user.click(await reachReview(user));

    const dialog = await screen.findByTestId("grant-required-dialog");
    // One dialog for both members, naming both.
    expect(screen.getAllByTestId(`grant-issue-${KEY}`)).toHaveLength(1);
    expect(dialog).toHaveTextContent("Alice");
    expect(dialog).toHaveTextContent("Bob");
    // Nothing is deployed, and no group is saved, while the question is open.
    expect(calls.some((c) => c.startsWith("deploy"))).toBe(false);
    expect(calls).not.toContain("group");

    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(groupBodies).toHaveLength(1));

    // Both members were created WITHOUT deploying.
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
    // The group is saved only after every member is live.
    expect(calls.indexOf("group")).toBeGreaterThan(calls.lastIndexOf("deploy new-agent-2"));
    expect(groupBodies[0]!.members.map((m) => m.agentId)).toEqual(["new-agent-1", "new-agent-2"]);
  });

  it("cancel deploys nothing, saves no group, says so - and a retry deploys the members it already made", async () => {
    serve();
    const user = userEvent.setup();
    await user.click(await reachReview(user));

    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-cancel"));

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    const message = String(vi.mocked(toast.error).mock.calls[0]![0]);
    expect(message).toMatch(/not deployed/i);
    expect(message).toMatch(/agent pages/i);
    expect(calls.some((c) => c.startsWith("deploy") || c.startsWith("grant"))).toBe(false);
    expect(calls).not.toContain("group");
    expect(toast.success).not.toHaveBeenCalled();

    // Try again: the members exist, so they are NOT created a second time -
    // but they are deployed this time.
    await user.click(await screen.findByTestId("group-wizard-create"));
    await screen.findByTestId("grant-required-dialog");
    await user.click(screen.getByTestId("grant-required-confirm"));
    await waitFor(() => expect(groupBodies).toHaveLength(1));
    expect(setupBodies).toHaveLength(2);
    expect(calls.filter((c) => c.startsWith("deploy"))).toEqual(["deploy new-agent-1", "deploy new-agent-2"]);
  });
});
