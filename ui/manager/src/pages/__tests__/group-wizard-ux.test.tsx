import { describe, expect, it } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { GroupWizardPage } from "@/pages/group-wizard";

type User = ReturnType<typeof userEvent.setup>;

async function applyBulkKey(user: User) {
  await user.type(await screen.findByTestId("gw-bulk-apikey-input"), "sk-test-key");
  await user.click(screen.getByTestId("gw-bulk-apply"));
}

/** Advisory-board template (5 new members + a moderator) up to the Members step. */
async function reachMembersWithTemplate(user: User) {
  renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
  await user.click(screen.getByTestId("template-advisory-board"));
  await user.click(screen.getByTestId("group-wizard-next"));
  await waitFor(() => expect(screen.getByTestId("gw-add-member")).toBeInTheDocument());
}

describe("GroupWizardPage — retry after a failed create", () => {
  // Provisioning is not idempotent. The member loop already wrote its progress
  // back on failure; the moderator branch returned without doing so, so a retry
  // found every member slot still uncreated and deployed a second copy of each.
  it("does not provision members a second time when the moderator step failed", async () => {
    const setupNames: string[] = [];
    let moderatorFailures = 0;
    let groupPosted = false;
    server.use(
      http.post("*/administration/agents/setup", async ({ request }) => {
        const body = (await request.json()) as { name: string };
        setupNames.push(body.name);
        if (body.name.endsWith("Moderator") && moderatorFailures === 0) {
          moderatorFailures += 1;
          return HttpResponse.json({ message: "LLM provider unreachable" }, { status: 500 });
        }
        return HttpResponse.json({
          agentId: `agent-${setupNames.length}`,
          agentName: body.name,
          deployed: true,
          deploymentStatus: "deployed",
        });
      }),
      http.post("*/groupstore/groups", () => {
        groupPosted = true;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/groupstore/groups/retry-grp?version=1" },
        });
      }),
    );

    const user = userEvent.setup();
    await reachMembersWithTemplate(user);
    await applyBulkKey(user);
    await user.click(screen.getByTestId("group-wizard-next"));

    // First attempt: five members succeed, the moderator fails.
    await user.click(await screen.findByTestId("group-wizard-create"));
    await waitFor(() => expect(moderatorFailures).toBe(1));
    await waitFor(() => expect(screen.getByTestId("group-wizard-create")).not.toBeDisabled());
    expect(setupNames).toHaveLength(6);
    expect(groupPosted).toBe(false);

    // Retry: only the moderator is provisioned again.
    await user.click(screen.getByTestId("group-wizard-create"));
    await waitFor(() => expect(groupPosted).toBe(true), { timeout: 15000 });
    expect(setupNames).toHaveLength(7);
    expect(setupNames.filter((n) => n.endsWith("Moderator"))).toHaveLength(2);
    // Every member name was set up exactly once.
    const memberNames = setupNames.filter((n) => !n.endsWith("Moderator"));
    expect(new Set(memberNames).size).toBe(memberNames.length);
  });
});

describe("GroupWizardPage — API keys and providers", () => {
  it("blocks Next with a per-member reason until a keyed provider has its key", async () => {
    const user = userEvent.setup();
    await reachMembersWithTemplate(user);

    expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    const blockers = screen.getByTestId("group-wizard-blockers");
    expect(blockers).toHaveTextContent(/Marketing Expert: add an API key for Anthropic/i);
    expect(blockers).toHaveTextContent(/Moderator: add an API key for Anthropic/i);
    expect(screen.getByTestId("gw-apikey-required-0")).toBeInTheDocument();

    await applyBulkKey(user);
    expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
    expect(screen.queryByTestId("group-wizard-blockers")).not.toBeInTheDocument();
  });

  it("lets a keyless provider through without a key", async () => {
    const user = userEvent.setup();
    await reachMembersWithTemplate(user);
    await user.selectOptions(screen.getByTestId("gw-bulk-provider"), "ollama");
    await user.click(screen.getByTestId("gw-bulk-apply"));

    expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
  });

  it("does not offer providers that setup cannot provision (Vertex AI)", async () => {
    const user = userEvent.setup();
    await reachMembersWithTemplate(user);

    const card = screen.getByTestId("member-card-0");
    const provider = within(card).getByRole("combobox", { name: "LLM Provider" });
    const values = within(provider)
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);
    expect(values).toContain("anthropic");
    expect(values).not.toContain("gemini-vertex");
    const bulkValues = within(screen.getByTestId("gw-bulk-provider"))
      .getAllByRole("option")
      .map((o) => (o as HTMLOptionElement).value);
    expect(bulkValues).not.toContain("gemini-vertex");
  });
});

describe("GroupWizardPage — why Next is disabled", () => {
  it("says what is missing on the setup step and clears it once fixed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
    await user.click(screen.getByTestId("template-blank"));

    expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    expect(screen.getByTestId("group-wizard-blockers")).toHaveTextContent("Enter a group name.");

    await user.type(screen.getByTestId("gw-name"), "Named");
    expect(screen.queryByTestId("group-wizard-blockers")).not.toBeInTheDocument();
  });

  it("lists every incomplete member on the members step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "G");
    await user.click(screen.getByTestId("group-wizard-next"));

    await user.click(screen.getByTestId("gw-add-member"));
    const blockers = screen.getByTestId("group-wizard-blockers");
    expect(blockers).toHaveTextContent("Add at least 2 members to proceed");
    expect(blockers).toHaveTextContent("Member 1: add a display name.");
  });
});

describe("GroupWizardPage — stepper and style picker semantics", () => {
  it("labels each step and marks the current one", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });

    const steps = screen.getByTestId("group-wizard-steps");
    expect(within(steps).getByText("Preset")).toBeInTheDocument();
    expect(within(steps).getByText("Members")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Step 1: Preset" })).toHaveAttribute("aria-current", "step");
    expect(screen.getByRole("button", { name: "Step 2: Setup" })).not.toHaveAttribute("aria-current");

    await user.click(screen.getByTestId("template-blank"));
    expect(screen.getByRole("button", { name: "Step 2: Setup" })).toHaveAttribute("aria-current", "step");
  });

  it("exposes the discussion styles as a radiogroup with best-for text and a rounds estimate", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
    await user.click(screen.getByTestId("template-advisory-board"));

    const group = screen.getByRole("radiogroup", { name: /Discussion Style/i });
    const roundTable = within(group).getByTestId("gw-style-ROUND_TABLE");
    expect(roundTable).toHaveAttribute("role", "radio");
    expect(roundTable).toHaveAttribute("aria-checked", "true");
    expect(within(group).getByTestId("gw-style-DEBATE")).toHaveAttribute("aria-checked", "false");
    expect(roundTable).toHaveTextContent(/Open brainstorming/);

    await user.click(within(group).getByTestId("gw-style-DEBATE"));
    expect(within(group).getByTestId("gw-style-DEBATE")).toHaveAttribute("aria-checked", "true");
    expect(within(group).getByTestId("gw-style-ROUND_TABLE")).toHaveAttribute("aria-checked", "false");
    expect(screen.getByTestId("gw-style-details")).toHaveTextContent(/How it runs/);

    // Advisory board: 5 members × 2 rounds + 1 moderator = 11.
    const hint = screen.getByTestId("gw-rounds-hint");
    expect(hint).toHaveTextContent(/One round is every member speaking once/);
    expect(hint).toHaveTextContent(/about 11 LLM calls/);
    fireEvent.change(screen.getByLabelText(/Max Rounds/i), { target: { value: "4" } });
    expect(screen.getByTestId("gw-rounds-hint")).toHaveTextContent(/about 21 LLM calls/);
  });
});

describe("GroupWizardPage — unsaved work", () => {
  it("only intercepts reload once something has been configured", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });

    const pristine = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(pristine);
    expect(pristine.defaultPrevented).toBe(false);

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Half-built");

    const dirty = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
  });

  it("asks before 'Back to Groups' discards a configured team", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Half-built");

    await user.click(screen.getByTestId("back-to-list"));
    expect(await screen.findByRole("alertdialog")).toHaveTextContent("Leave without creating?");

    // Cancel keeps the wizard and its state.
    await user.click(screen.getByTestId("unsaved-cancel"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(screen.getByTestId("gw-name")).toHaveValue("Half-built");
  });

  it("does not ask when nothing has been configured", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });

    await user.click(screen.getByTestId("back-to-list"));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });
});

describe("GroupWizardPage — presets and role chips", () => {
  it("labels the wizard presets, links to the packaged templates, and humanises role chips", async () => {
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });

    const note = screen.getByTestId("presets-note");
    expect(note).toHaveTextContent(/starter presets/i);
    expect(within(note).getByRole("link", { name: "Group Templates" })).toHaveAttribute(
      "href",
      "/manage/groups/templates",
    );

    // Four analysts all carrying "Forecasting" are one chip, not four.
    const forecasting = screen.getByTestId("template-forecasting");
    expect(within(forecasting).getAllByText(/Forecasting/)).toHaveLength(1);
    expect(forecasting).toHaveTextContent("Forecasting ×4");
    // The enum spelling is gone.
    const riskAssessment = screen.getByTestId("template-risk-assessment");
    expect(riskAssessment).toHaveTextContent("Devil advocate");
    expect(riskAssessment).not.toHaveTextContent("DEVIL_ADVOCATE");
  });
});

describe("GroupWizardPage — human turn timeout", () => {
  it("is picked as number + unit and submitted as an ISO-8601 duration", async () => {
    let submitted: { humanMemberConfig?: { turnTimeout?: string | null } } | null = null;
    server.use(
      http.post("*/administration/agents/setup", () =>
        HttpResponse.json({ agentId: "auto-agent-1", deployed: true, deploymentStatus: "deployed" }),
      ),
      http.post("*/groupstore/groups", async ({ request }) => {
        submitted = (await request.json()) as typeof submitted;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/groupstore/groups/new-grp?version=1" },
        });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Humans");
    await user.click(screen.getByTestId("group-wizard-next"));
    await user.click(screen.getByTestId("gw-add-member"));
    await user.click(screen.getByTestId("gw-add-member"));
    await user.type(screen.getByTestId("member-name-0"), "Director");
    await user.click(screen.getByTestId("member-type-human-0"));
    await user.type(screen.getByTestId("human-principal-id-0"), "director@acme.com");
    await user.type(screen.getByTestId("member-name-1"), "Agent Two");
    await applyBulkKey(user);

    await user.type(screen.getByTestId("human-turn-timeout-input"), "36");
    await user.selectOptions(screen.getByTestId("human-turn-timeout-input-unit"), "hours");
    await user.click(screen.getByTestId("group-wizard-next"));
    await user.click(await screen.findByTestId("group-wizard-create"));

    await waitFor(() => expect(submitted).not.toBeNull());
    expect(submitted!.humanMemberConfig?.turnTimeout).toBe("PT36H");
  });
});
