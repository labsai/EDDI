import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { server } from "@/test/mocks/server";
import { renderWithProviders } from "@/test/test-utils";
import { GroupWizardPage } from "@/pages/group-wizard";

/**
 * "Create Agent" on a member card provisions and deploys that agent on its own,
 * while the person keeps working. Every write-back used to rebuild the whole
 * member list from the render in which the button was clicked: a second card
 * created at the same time, an edit to another card or a removal was reverted
 * when the first finished - and a card set back to "not created" was then
 * provisioned again by the final Create, leaving the first agent deployed with
 * nothing pointing at it.
 */

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
}));

type User = ReturnType<typeof userEvent.setup>;

/** One `setup` call the test answers when it chooses (`true` = created). */
let pending: Array<(ok: boolean) => void>;
/** Answer every `setup` straight away instead of holding it. */
let autoRespond: boolean;
let setupCount: number;
let groupBodies: Array<{ members: Array<{ agentId: string; displayName: string }>; moderatorAgentId: string | null }>;

function serve() {
  server.use(
    http.post("*/administration/agents/setup", async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      setupCount += 1;
      const n = setupCount;
      const ok = autoRespond ? true : await new Promise<boolean>((resolve) => pending.push(resolve));
      if (!ok) return HttpResponse.json({ error: "provider unavailable" }, { status: 500 });
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
    http.get("*/administration/:env/deploy/:agentId/preflight", ({ params }) =>
      HttpResponse.json({
        agentId: params.agentId,
        version: 1,
        enforcement: "OFF",
        checked: true,
        ready: true,
        grantIssues: [],
      }),
    ),
    http.post("*/administration/:env/deploy/:agentId", ({ params }) =>
      HttpResponse.json({ status: "READY", agentId: params.agentId, version: 1, environment: "production" }),
    ),
    http.post("*/groupstore/groups", async ({ request }) => {
      groupBodies.push((await request.json()) as (typeof groupBodies)[number]);
      return new HttpResponse(null, {
        status: 201,
        headers: { Location: "/groupstore/groups/new-grp?version=1" },
      });
    }),
  );
}

/** Blank preset, two members (Alice, Bob) with a key, optionally a moderator. */
async function reachMembers(user: User, { moderator = false } = {}) {
  renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
  await user.click(screen.getByTestId("template-blank"));
  await user.type(screen.getByTestId("gw-name"), "Slot Group");
  await user.click(screen.getByTestId("group-wizard-next"));
  await user.click(screen.getByTestId("gw-add-member"));
  await user.click(screen.getByTestId("gw-add-member"));
  await user.type(await screen.findByTestId("member-name-0"), "Alice");
  await user.type(screen.getByTestId("member-name-1"), "Bob");
  if (moderator) await user.click(screen.getByRole("button", { name: /add moderator/i }));
  await user.type(await screen.findByTestId("gw-bulk-apikey-input"), "sk-test-key");
  await user.click(screen.getByTestId("gw-bulk-apply"));
}

async function heldSetups(count: number) {
  await waitFor(() => expect(pending).toHaveLength(count));
}

async function saveGroup(user: User) {
  autoRespond = true;
  await user.click(screen.getByTestId("group-wizard-next"));
  const create = await screen.findByTestId("group-wizard-create");
  await waitFor(() => expect(create).toBeEnabled());
  await user.click(create);
  await waitFor(() => expect(groupBodies).toHaveLength(1));
  return groupBodies[0]!;
}

describe("GroupWizardPage - Create Agent on a member card", () => {
  beforeEach(() => {
    pending = [];
    autoRespond = false;
    setupCount = 0;
    groupBodies = [];
    vi.clearAllMocks();
    serve();
  });

  afterEach(() => {
    // Nothing may stay held past the test.
    for (const respond of pending) respond(true);
  });

  it("two cards created at once both keep their agent, and the final Create provisions no third", async () => {
    const user = userEvent.setup();
    await reachMembers(user);

    await user.click(screen.getByTestId("gw-create-agent-0"));
    await user.click(screen.getByTestId("gw-create-agent-1"));
    await heldSetups(2);
    // The later click finishes first.
    pending[1]!(true);
    pending[0]!(true);

    await waitFor(() => expect(screen.getAllByText("Agent created & deployed")).toHaveLength(2));
    const group = await saveGroup(user);
    expect(setupCount).toBe(2);
    expect(group.members.map((m) => m.agentId)).toEqual(["new-agent-1", "new-agent-2"]);
  });

  it("an edit to another card while one provisions is kept", async () => {
    const user = userEvent.setup();
    await reachMembers(user);

    await user.click(screen.getByTestId("gw-create-agent-0"));
    await heldSetups(1);
    await user.type(screen.getByTestId("member-name-1"), "by");
    pending[0]!(true);

    await screen.findByText("Agent created & deployed");
    expect(screen.getByTestId("member-name-1")).toHaveValue("Bobby");
    const group = await saveGroup(user);
    expect(group.members.map((m) => [m.displayName, m.agentId])).toEqual([
      ["Alice", "new-agent-1"],
      ["Bobby", "new-agent-2"],
    ]);
  });

  it("removing another card while one provisions keeps the agent on the card that asked for it", async () => {
    const user = userEvent.setup();
    await reachMembers(user);

    await user.click(screen.getByTestId("gw-create-agent-1"));
    await heldSetups(1);
    await user.click(screen.getByTestId("remove-member-0"));
    pending[0]!(true);

    await screen.findByText("Agent created & deployed");
    expect(screen.queryByTestId("member-card-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("member-name-0")).toHaveValue("Bob");
  });

  it("while a card provisions, it cannot be removed or retyped, and the group cannot be created", async () => {
    const user = userEvent.setup();
    await reachMembers(user);

    await user.click(screen.getByTestId("gw-create-agent-0"));
    await heldSetups(1);
    // Removing it, or turning it into a human, would leave its agent behind.
    expect(screen.getByTestId("remove-member-0")).toBeDisabled();
    expect(screen.getByTestId("member-type-human-0")).toBeDisabled();
    expect(screen.getByTestId("remove-member-1")).toBeEnabled();

    // The final Create would provision the same card a second time.
    await user.click(screen.getByTestId("group-wizard-next"));
    const create = await screen.findByTestId("group-wizard-create");
    expect(create).toBeDisabled();

    pending[0]!(true);
    await waitFor(() => expect(create).toBeEnabled());
    expect(setupCount).toBe(1);
  });

  it("while a card provisions, the wizard cannot go back to the preset step and replace the cards", async () => {
    const user = userEvent.setup();
    await reachMembers(user);

    await user.click(screen.getByTestId("gw-create-agent-0"));
    await heldSetups(1);
    // Picking a preset replaces every card, so the agent would find no card to
    // write back to and be left deployed with nothing pointing at it.
    expect(screen.getByTestId("group-wizard-back")).toBeDisabled();
    expect(screen.getByTestId("group-wizard-step-template")).toBeDisabled();
    expect(screen.getByTestId("group-wizard-step-config")).toBeDisabled();
    await user.click(screen.getByTestId("group-wizard-step-template"));
    expect(screen.queryByTestId("template-grid")).not.toBeInTheDocument();

    pending[0]!(true);
    await screen.findByText("Agent created & deployed");
    expect(screen.getByTestId("group-wizard-back")).toBeEnabled();
    expect(screen.getByTestId("group-wizard-step-template")).toBeEnabled();
  });

  it("a moderator edit made while it provisions survives a failed create, and a retry saves it", async () => {
    const user = userEvent.setup();
    await reachMembers(user, { moderator: true });

    await user.click(screen.getByTestId("gw-create-moderator"));
    await heldSetups(1);
    expect(screen.getByTitle("Remove moderator")).toBeDisabled();
    await user.type(screen.getByPlaceholderText(/skilled moderator/i), "Be brief.");
    pending[0]!(false);

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    expect(screen.getByPlaceholderText(/skilled moderator/i)).toHaveValue("Be brief.");

    autoRespond = true;
    await user.click(screen.getByTestId("gw-create-moderator"));
    await screen.findByText("Moderator agent ready");
    const group = await saveGroup(user);
    // The failed attempt, the retry, then the two members.
    expect(setupCount).toBe(4);
    expect(group.moderatorAgentId).toBe("new-agent-2");
  });

  it("during the final Create, Back and the step buttons are disabled", async () => {
    const user = userEvent.setup();
    await reachMembers(user);
    await user.click(screen.getByTestId("group-wizard-next"));
    await user.click(await screen.findByTestId("group-wizard-create"));
    // The batch provisions both members at once.
    await heldSetups(2);

    // Going back to Members mid-create let edits be overwritten by the
    // create's own copy of the members, and a card be provisioned twice.
    expect(screen.getByTestId("group-wizard-back")).toBeDisabled();
    expect(screen.getByTestId("group-wizard-step-members")).toBeDisabled();
    expect(screen.getByTestId("group-wizard-step-config")).toBeDisabled();

    autoRespond = true;
    for (const respond of pending.splice(0)) respond(true);
    await waitFor(() => expect(groupBodies).toHaveLength(1));
  });
});
