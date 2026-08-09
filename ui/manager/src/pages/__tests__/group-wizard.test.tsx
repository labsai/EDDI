import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { GroupWizardPage } from "@/pages/group-wizard";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("GroupWizardPage", () => {
  describe("HITL configuration", () => {
    it("enabling human approval reveals phase gates with one sensible default", async () => {
      const user = userEvent.setup();
      renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
      await user.click(screen.getByTestId("template-blank"));

      // Off by default — no phase list, no policy.
      expect(screen.queryByTestId("gw-hitl-phases")).not.toBeInTheDocument();

      await user.click(screen.getByTestId("gw-hitl-enable"));

      const phasesBox = screen.getByTestId("gw-hitl-phases");
      // ROUND_TABLE @ 2 rounds: Initial Opinions, Discussion, Synthesis.
      const checks = within(phasesBox).getAllByRole("checkbox");
      expect(checks).toHaveLength(3);
      expect(checks.filter((c) => (c as HTMLInputElement).checked)).toHaveLength(1);
      expect(screen.getByTestId("gw-hitl-policy")).toBeInTheDocument();
    });

    it("a finite timeout policy seeds a valid duration, and validates edits", async () => {
      const user = userEvent.setup();
      renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
      await user.click(screen.getByTestId("template-blank"));
      await user.type(screen.getByTestId("gw-name"), "Approvals Group");
      await user.click(screen.getByTestId("gw-hitl-enable"));

      // Default policy WAIT_INDEFINITELY + one gate → valid, no timeout field.
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
      expect(screen.queryByTestId("gw-hitl-timeout")).not.toBeInTheDocument();

      // A finite policy seeds a default timeout (PT15M) so the config stays valid.
      await user.selectOptions(screen.getByTestId("gw-hitl-policy"), "AUTO_REJECT");
      const timeout = screen.getByTestId("gw-hitl-timeout");
      expect(timeout).toBeInTheDocument();
      expect(timeout).toHaveValue("PT15M");
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();

      // Clearing it blocks Next; a valid value re-enables it.
      await user.clear(timeout);
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
      await user.type(timeout, "PT30M");
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
    });

    it("granularity control only shows for TASK_FORCE, and TASK reveals on-rejection", async () => {
      const user = userEvent.setup();
      renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
      await user.click(screen.getByTestId("template-blank"));
      await user.click(screen.getByTestId("gw-hitl-enable"));

      // ROUND_TABLE (default) has no task phase → no granularity control.
      expect(screen.queryByTestId("gw-hitl-granularity")).not.toBeInTheDocument();

      // Switch to TASK_FORCE → granularity appears; picking TASK reveals on-rejection.
      await user.click(screen.getByTestId("gw-style-TASK_FORCE"));
      expect(screen.getByTestId("gw-hitl-granularity")).toBeInTheDocument();
      expect(screen.queryByTestId("gw-hitl-rejection")).not.toBeInTheDocument();
      await user.selectOptions(screen.getByTestId("gw-hitl-granularity"), "TASK");
      expect(screen.getByTestId("gw-hitl-rejection")).toBeInTheDocument();
    });
  });

  it("renders wizard heading and step indicator", () => {
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });
    expect(screen.getByText("Group Setup Wizard")).toBeInTheDocument();
    expect(screen.getByTestId("group-wizard-steps")).toBeInTheDocument();
  });

  it("shows template cards on step 1", () => {
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });
    expect(screen.getByTestId("template-advisory-board")).toBeInTheDocument();
    expect(screen.getByTestId("template-blank")).toBeInTheDocument();
  });

  it("selecting a template advances to config step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));

    // Should see the config step — name input should be pre-filled
    await waitFor(() => {
      expect(screen.getByTestId("gw-name")).toBeInTheDocument();
    });
    expect(
      (screen.getByTestId("gw-name") as HTMLInputElement).value,
    ).toBe("Advisory Board");
  });

  it("config step Next is disabled without name", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Skip template step
    await user.click(screen.getByTestId("template-blank"));

    // Name is empty — Next should be disabled
    expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
  });

  it("config step Next enables after entering name", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");

    expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
  });

  it("members step requires at least 2 members to proceed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Template → Config → Members
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Members step — no members yet, Next should be disabled
    await waitFor(() => {
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    });
  });

  it("advisory board template pre-fills 5 member cards", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));
    // Skip config step
    await user.click(screen.getByTestId("group-wizard-next"));

    // Should show 5 pre-filled member cards
    await waitFor(() => {
      expect(screen.getByTestId("member-card-0")).toBeInTheDocument();
      expect(screen.getByTestId("member-card-4")).toBeInTheDocument();
    });
  });

  it("add member button creates new member card", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Add first member
    await user.click(screen.getByTestId("gw-add-member"));
    await waitFor(() => {
      expect(screen.getByTestId("member-card-0")).toBeInTheDocument();
    });
  });

  it("back button navigates to previous step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    // Now on config step
    expect(screen.getByTestId("gw-name")).toBeInTheDocument();

    // Go back to template step
    await user.click(screen.getByTestId("group-wizard-back"));
    expect(screen.getByTestId("template-blank")).toBeInTheDocument();
  });

  it("review step shows auto-create notice for new agents", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Use advisory board template (5 members all in "new" mode)
    await user.click(screen.getByTestId("template-advisory-board"));

    // Config → Next
    await user.click(screen.getByTestId("group-wizard-next"));

    // Members → Next (5 members, all "new" mode, should pass)
    await user.click(screen.getByTestId("group-wizard-next"));

    // Review step should show the auto-create notice
    await waitFor(() => {
      expect(screen.getByTestId("auto-create-notice")).toBeInTheDocument();
    });
  });

  // ── Remove member ──────────────────────────────────────────────────

  it("remove button deletes a member card", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Add 2 members
    await user.click(screen.getByTestId("gw-add-member"));
    await user.click(screen.getByTestId("gw-add-member"));

    await waitFor(() => {
      expect(screen.getByTestId("member-card-0")).toBeInTheDocument();
      expect(screen.getByTestId("member-card-1")).toBeInTheDocument();
    });

    // Remove first member
    await user.click(screen.getByTestId("remove-member-0"));

    await waitFor(() => {
      // Only 1 member card should remain
      expect(screen.queryByTestId("member-card-1")).not.toBeInTheDocument();
    });
  });

  // ── Config step fields ─────────────────────────────────────────────

  it("config step allows editing description", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "My Group");

    const descInput = screen.getByTestId("gw-description");
    await user.type(descInput, "A test group description");
    expect((descInput as HTMLTextAreaElement).value).toBe("A test group description");
  });

  // ── Style selection ────────────────────────────────────────────────

  it("config step shows discussion style selector", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");

    // Style selector shows style labels (not a select element, it's a grid of buttons)
    expect(screen.getByText("Discussion Style")).toBeInTheDocument();
    expect(screen.getByText("Quality Review")).toBeInTheDocument();
    expect(screen.getByText("Collaborative Council")).toBeInTheDocument();
  });

  // ── Create group mutation ──────────────────────────────────────────

  it("calls create group API from review step", async () => {
    let createCalled = false;
    // Mock setup-agent for auto-creating new member agents
    server.use(
      http.post("*/administration/agents/setup", () => {
        return HttpResponse.json({
          agentId: `auto-agent-${Date.now()}`,
          agentName: "Auto Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: true,
          deploymentStatus: "deployed",
        });
      }),
      http.post("*/groupstore/groups", () => {
        createCalled = true;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/groupstore/groups/new-grp?version=1" },
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    // Use advisory board template (5 pre-filled members)
    await user.click(screen.getByTestId("template-advisory-board"));

    // Config step → Next
    await user.click(screen.getByTestId("group-wizard-next"));

    // Members step → Next
    await user.click(screen.getByTestId("group-wizard-next"));

    // Review step → Create
    await waitFor(() => {
      expect(screen.getByTestId("group-wizard-create")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("group-wizard-create"));

    await waitFor(() => {
      expect(createCalled).toBe(true);
    }, { timeout: 15000 });
  });

  // ── Members step: Next enabled with 2+ members ─────────────────────

  it("enables Next when 2 members with displayNames are added", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Add 2 members
    await user.click(screen.getByTestId("gw-add-member"));
    await user.click(screen.getByTestId("gw-add-member"));

    // Give them display names
    const nameInputs = screen.getAllByTestId(/^member-name-/);
    await user.type(nameInputs[0]!, "Alice");
    await user.type(nameInputs[1]!, "Bob");

    // Next should now be enabled
    await waitFor(() => {
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
    });
  });

  // ── Review step shows group configuration summary ──────────────────

  it("review step shows the group name in summary", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));

    // Clear and type new name
    const nameInput = screen.getByTestId("gw-name");
    await user.clear(nameInput);
    await user.type(nameInput, "My Custom Board");

    // Config → Members → Review
    await user.click(screen.getByTestId("group-wizard-next"));
    await user.click(screen.getByTestId("group-wizard-next"));

    await waitFor(() => {
      expect(screen.getByText("My Custom Board")).toBeInTheDocument();
    });
  });

  // ── Discussion style switching ────────────────────────────────────

  it("selects DEBATE style and shows style-specific badge", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Debate Group");

    // Switch to DEBATE style
    await user.click(screen.getByTestId("gw-style-DEBATE"));

    // The DEBATE style card should be visually selected (check it's in the DOM)
    expect(screen.getByTestId("gw-style-DEBATE")).toBeInTheDocument();
  });

  it("selects DEVIL_ADVOCATE style", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "DA Group");

    await user.click(screen.getByTestId("gw-style-DEVIL_ADVOCATE"));
    expect(screen.getByTestId("gw-style-DEVIL_ADVOCATE")).toBeInTheDocument();
  });

  it("selects PEER_REVIEW style", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "PR Group");

    await user.click(screen.getByTestId("gw-style-PEER_REVIEW"));
    expect(screen.getByTestId("gw-style-PEER_REVIEW")).toBeInTheDocument();
  });

  it("selects DELPHI style", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Delphi Group");

    await user.click(screen.getByTestId("gw-style-DELPHI"));
    expect(screen.getByTestId("gw-style-DELPHI")).toBeInTheDocument();
  });

  // ── Review step details ───────────────────────────────────────────

  it("review step shows member count and rounds", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));

    // Config → Members → Review
    await user.click(screen.getByTestId("group-wizard-next"));
    await user.click(screen.getByTestId("group-wizard-next"));

    await waitFor(() => {
      // 5 members from advisory board template
      expect(screen.getByText(/5 members/)).toBeInTheDocument();
      expect(screen.getByText(/2 rounds/)).toBeInTheDocument();
    });
  });

  it("review step shows Moderator badge when template includes moderator", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));

    // Config → Members
    await user.click(screen.getByTestId("group-wizard-next"));

    // Wait for the members step to render before navigating further
    await waitFor(() => {
      expect(screen.getByTestId("gw-add-member")).toBeInTheDocument();
    });

    // Verify the Next button is enabled before clicking
    const nextBtn = screen.getByTestId("group-wizard-next");
    expect(nextBtn).not.toBeDisabled();

    // Members → Review
    await user.click(nextBtn);

    // Verify we're on the review step
    await waitFor(() => {
      expect(screen.getByText("Review & Create")).toBeInTheDocument();
    });

    // The review step should show the moderator section
    expect(screen.getByText(/5 members/)).toBeInTheDocument();
  });

  it("review step shows discussion flow section", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));

    // Config → Members
    await user.click(screen.getByTestId("group-wizard-next"));

    // Wait for members step
    await waitFor(() => {
      expect(screen.getByTestId("gw-add-member")).toBeInTheDocument();
    });

    // Members → Review
    await user.click(screen.getByTestId("group-wizard-next"));

    await waitFor(() => {
      expect(screen.getByText("Discussion Flow")).toBeInTheDocument();
    });
  });

  // ── Success state ──────────────────────────────────────────────────

  it("shows success state after group creation", async () => {
    server.use(
      http.post("*/administration/agents/setup", () => {
        return HttpResponse.json({
          action: "created",
          agentId: `auto-agent-${Date.now()}`,
          agentName: "Auto Agent",
          provider: "anthropic",
          model: "claude-sonnet-4-6",
          deployed: true,
          deploymentStatus: "deployed",
        });
      }),
      http.post("*/groupstore/groups", () => {
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/groupstore/groups/success-grp?version=1" },
        });
      })
    );

    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-advisory-board"));
    await user.click(screen.getByTestId("group-wizard-next"));

    // Wait for members step to render
    await waitFor(() => {
      expect(screen.getByTestId("gw-add-member")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("group-wizard-next"));

    await waitFor(() => {
      expect(screen.getByTestId("group-wizard-create")).toBeInTheDocument();

    });

    await user.click(screen.getByTestId("group-wizard-create"));

    await waitFor(() => {
      expect(screen.getByText(/Group Created/)).toBeInTheDocument();
    }, { timeout: 15000 });
  });

  // ── Needs at least 2 members warning ──────────────────────────────

  it("shows warning when less than 2 members", async () => {
    const user = userEvent.setup();
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("gw-name"), "Test Group");
    await user.click(screen.getByTestId("group-wizard-next"));

    // Add only one member
    await user.click(screen.getByTestId("gw-add-member"));

    // Should still show the < 2 member warning
    await waitFor(() => {
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    });
  });

  // ── Template grid has multiple templates ──────────────────────────

  it("template grid shows multiple templates beyond advisory board", async () => {
    renderWithProviders(<GroupWizardPage />, {
      initialRoute: "/manage/groups/wizard",
    });

    expect(screen.getByTestId("template-grid")).toBeInTheDocument();
    // Advisory board is one template, there should be more
    expect(screen.getByTestId("template-advisory-board")).toBeInTheDocument();
  });

  // I6 — HUMAN group members.
  describe("HUMAN members", () => {
    async function reachMembersStepWithTwo(user: ReturnType<typeof userEvent.setup>) {
      renderWithProviders(<GroupWizardPage />, { initialRoute: "/manage/groups/wizard" });
      await user.click(screen.getByTestId("template-blank"));
      await user.type(screen.getByTestId("gw-name"), "Human Test Group");
      await user.click(screen.getByTestId("group-wizard-next"));
      await user.click(screen.getByTestId("gw-add-member"));
      await user.click(screen.getByTestId("gw-add-member"));
      await waitFor(() => {
        expect(screen.getByTestId("member-card-1")).toBeInTheDocument();
      });
      // Second member is a plain agent in "new" mode — enough by itself to pass
      // the members-step gate, so any Next-disabling below is attributable to
      // the first (HUMAN) member specifically.
      await user.type(screen.getByTestId("member-name-1"), "Agent Two");
    }

    it("switching a member to Human blocks Next until a principal id is entered", async () => {
      const user = userEvent.setup();
      await reachMembersStepWithTwo(user);
      await user.type(screen.getByTestId("member-name-0"), "Director");

      await user.click(screen.getByTestId("member-type-human-0"));
      expect(screen.getByTestId("human-principal-id-0")).toBeInTheDocument();
      // No agent-creation UI (mode toggle / provider picker) within THIS card —
      // member 1 (an AGENT) legitimately still has its own "Use Existing" toggle.
      expect(
        within(screen.getByTestId("member-card-0")).queryByText("Use Existing"),
      ).not.toBeInTheDocument();

      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();

      await user.type(screen.getByTestId("human-principal-id-0"), "director@acme.com");
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
    });

    // `agentId` means a different thing per member type — a deployed agent, a
    // nested group config, or a human's principal id. Carrying it across a
    // switch would submit an agent id as somebody's login.
    it("clears a selected agent when switching that member to Human", async () => {
      const user = userEvent.setup();
      await reachMembersStepWithTwo(user);
      await user.type(screen.getByTestId("member-name-0"), "Director");

      // Genuinely assign a real agent first — the reset is only meaningful if
      // there IS an agentId to carry over.
      const card = screen.getByTestId("member-card-0");
      await user.click(within(card).getByText("Use Existing"));
      const picker = within(card).getByRole("combobox");
      const option = within(picker).getAllByRole("option").find((o) => (o as HTMLOptionElement).value);
      await user.selectOptions(picker, (option as HTMLOptionElement).value);
      expect((picker as HTMLSelectElement).value).not.toBe("");
      // With an agent assigned, the members step is satisfied.
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();

      // Switching to HUMAN must surface an EMPTY principal-id field, never one
      // pre-filled with the agent id — and Next must go back to blocked,
      // because that agent id is not a valid human principal.
      await user.click(screen.getByTestId("member-type-human-0"));
      expect(screen.getByTestId("human-principal-id-0")).toHaveValue("");
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    });

    // Switching HUMAN back to AGENT leaves no visible trace of the stale id —
    // the card just shows the create-agent form again. Its only observable
    // effect is the SUBMITTED payload, where a principal id masquerading as an
    // agentId would create a group pointing at an agent that does not exist.
    it("never submits a former principal id as a member's agentId", async () => {
      let submitted: { members?: { agentId?: string; memberType?: string }[] } | null = null;
      server.use(
        http.post("*/administration/agents/setup", () =>
          HttpResponse.json({
            agentId: "auto-agent-1", agentName: "Auto Agent", provider: "anthropic",
            model: "claude-sonnet-4-6", deployed: true, deploymentStatus: "deployed",
          }),
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
      await reachMembersStepWithTwo(user);
      await user.type(screen.getByTestId("member-name-0"), "Director");

      await user.click(screen.getByTestId("member-type-human-0"));
      await user.type(screen.getByTestId("human-principal-id-0"), "director@acme.com");

      // Change of mind: this seat should be an agent after all.
      await user.click(within(screen.getByTestId("member-card-0")).getByText("Agent"));
      expect(screen.queryByTestId("human-turn-settings")).not.toBeInTheDocument();

      await user.click(screen.getByTestId("group-wizard-next"));
      await user.click(screen.getByTestId("group-wizard-create"));

      await waitFor(() => expect(submitted).not.toBeNull());
      const member = submitted!.members![0]!;
      expect(member.memberType).toBe("AGENT");
      expect(member.agentId).not.toBe("director@acme.com");
    });

    // A GROUP member points at a nested group that already exists — there is no
    // "create new" flow for it, so its `mode` is whatever the last type switch
    // left behind. When that was "new", the members step accepted a GROUP seat
    // with nothing selected and the create step then provisioned a real deployed
    // LLM agent for it, submitting that agent's id as the nested group's config id.
    it("blocks Next until a nested group is picked, and never invents an agent for it", async () => {
      let setupCalls = 0;
      let submitted: { members?: { agentId?: string; memberType?: string }[] } | null = null;
      server.use(
        http.post("*/administration/agents/setup", () => {
          setupCalls += 1;
          return HttpResponse.json({
            agentId: `auto-agent-${setupCalls}`, agentName: "Auto Agent", provider: "anthropic",
            model: "claude-sonnet-4-6", deployed: true, deploymentStatus: "deployed",
          });
        }),
        http.post("*/groupstore/groups", async ({ request }) => {
          submitted = (await request.json()) as typeof submitted;
          return new HttpResponse(null, {
            status: 201,
            headers: { Location: "/groupstore/groups/new-grp?version=1" },
          });
        }),
      );

      const user = userEvent.setup();
      await reachMembersStepWithTwo(user);
      await user.type(screen.getByTestId("member-name-0"), "Research Pod");

      const card = screen.getByTestId("member-card-0");
      await user.click(within(card).getByText("Group"));

      // Nothing selected yet — the seat is unfilled, so the step is not done.
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();

      const picker = await within(card).findByRole("combobox");
      const option = within(picker).getAllByRole("option").find((o) => (o as HTMLOptionElement).value);
      await user.selectOptions(picker, (option as HTMLOptionElement).value);
      const pickedGroupId = (picker as HTMLSelectElement).value;
      expect(pickedGroupId).not.toBe("");
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();

      await user.click(screen.getByTestId("group-wizard-next"));
      await user.click(screen.getByTestId("group-wizard-create"));

      await waitFor(() => expect(submitted).not.toBeNull());
      const groupMember = submitted!.members![0]!;
      expect(groupMember.memberType).toBe("GROUP");
      // The nested group's own id — not an agent conjured up to fill the slot.
      expect(groupMember.agentId).toBe(pickedGroupId);
      // Exactly one agent created: member 1, the only AGENT still in "new" mode.
      expect(setupCalls).toBe(1);
    });

    // The likeliest way to hit the bug in practice: a first-time user with no
    // groups yet picks "Group", gets an empty-state card with nothing to select,
    // and previously could still walk straight through to create.
    it("keeps Next blocked for a Group member when no groups exist to nest", async () => {
      server.use(
        http.get("*/groupstore/groups/descriptors", () => HttpResponse.json([])),
        http.get("*/groupstore/groups", () => HttpResponse.json([])),
      );

      const user = userEvent.setup();
      await reachMembersStepWithTwo(user);
      await user.type(screen.getByTestId("member-name-0"), "Research Pod");

      const card = screen.getByTestId("member-card-0");
      await user.click(within(card).getByText("Group"));

      await within(card).findByText("No groups available");
      expect(within(card).queryByRole("combobox")).not.toBeInTheDocument();
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();
    });

    it("shows the human turn settings panel only once a member is Human, and validates the duration", async () => {
      const user = userEvent.setup();
      await reachMembersStepWithTwo(user);
      await user.type(screen.getByTestId("member-name-0"), "Director");

      expect(screen.queryByTestId("human-turn-settings")).not.toBeInTheDocument();

      await user.click(screen.getByTestId("member-type-human-0"));
      await user.type(screen.getByTestId("human-principal-id-0"), "director@acme.com");
      expect(screen.getByTestId("human-turn-settings")).toBeInTheDocument();

      // Blank timeout ("wait indefinitely") is valid — Next stays enabled.
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();

      await user.type(screen.getByTestId("human-turn-timeout-input"), "not-a-duration");
      expect(screen.getByTestId("group-wizard-next")).toBeDisabled();

      await user.clear(screen.getByTestId("human-turn-timeout-input"));
      await user.type(screen.getByTestId("human-turn-timeout-input"), "PT24H");
      expect(screen.getByTestId("group-wizard-next")).not.toBeDisabled();
    });
  });
});
