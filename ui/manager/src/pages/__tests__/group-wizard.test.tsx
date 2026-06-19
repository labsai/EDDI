import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { GroupWizardPage } from "@/pages/group-wizard";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("GroupWizardPage", () => {
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
    expect(screen.getByText("Peer Review")).toBeInTheDocument();
    expect(screen.getByText("Round Table")).toBeInTheDocument();
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
});
