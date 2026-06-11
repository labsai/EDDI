import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateGroupDialog } from "../create-group-dialog";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("CreateGroupDialog", () => {
  const mockOnClose = vi.fn();

  beforeEach(() => {
    mockOnClose.mockReset();
    // Reset MSW handlers to default
    server.resetHandlers();
    
    // Default MSW mocks
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent1?version=1",
            name: "Customer Support Agent",
          },
          {
            resource: "eddi://ai.labs.agent/agentstore/agents/agent2?version=1",
            name: "Technical Support Agent",
          },
        ]);
      }),
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.group/groupstore/groups/group1?version=1",
            name: "Marketing Team",
            memberCount: 3,
          },
        ]);
      }),
      http.get("*/groupstore/groups/group1", () => {
        return HttpResponse.json({
          name: "Marketing Team",
          description: "Marketing and sales coordination",
          members: [
            { agentId: "agent1", displayName: "Marketer", speakingOrder: 1, role: "Marketing", memberType: "AGENT" }
          ],
          moderatorAgentId: null,
          style: "ROUND_TABLE",
          maxRounds: 2,
        });
      })
    );
  });

  it("does not render when open is false", () => {
    const { container } = renderWithProviders(
      <CreateGroupDialog open={false} onClose={mockOnClose} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders template selection step initially", () => {
    renderWithProviders(<CreateGroupDialog open={true} onClose={mockOnClose} />);

    expect(screen.getByText("Create Group")).toBeInTheDocument();
    expect(screen.getByText("Template")).toHaveClass("bg-primary");
    expect(screen.getByText("Start from Scratch")).toBeInTheDocument();
    expect(screen.getByText("Advisory Board")).toBeInTheDocument();
  });

  it("allows selecting a template to pre-fill basics step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateGroupDialog open={true} onClose={mockOnClose} />);

    // Select Advisory Board template
    const templateBtn = screen.getByTestId("template-advisory-board");
    await user.click(templateBtn);

    // Should switch to basics step
    expect(screen.getByText("Basics")).toHaveClass("bg-primary");
    expect(screen.getByTestId("group-name-input")).toHaveValue("Advisory Board");
  });

  it("allows starting blank and validates basics fields", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateGroupDialog open={true} onClose={mockOnClose} />);

    // Click Start from Scratch
    await user.click(screen.getByTestId("template-blank"));

    expect(screen.getByText("Basics")).toHaveClass("bg-primary");
    
    // Next button should be disabled because name is empty
    const nextBtn = screen.getByRole("button", { name: /Next/i });
    expect(nextBtn).toBeDisabled();

    // Type name
    await user.type(screen.getByTestId("group-name-input"), "Test Group");
    expect(nextBtn).toBeEnabled();

    // Select Discussion Style
    const styleBtn = screen.getByText("Peer Review");
    await user.click(styleBtn);

    // Transition to members
    await user.click(nextBtn);
    expect(screen.getByText("Members")).toHaveClass("bg-primary");
  });

  it("allows managing member roles and assignments", async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<CreateGroupDialog open={true} onClose={mockOnClose} />);

    // Start blank and name the group
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("group-name-input"), "Design Council");
    await user.click(screen.getByRole("button", { name: /Next/i }));

    // Now in members step, initially members is empty
    // Next button should be disabled because we need at least 2 members
    const nextBtn = screen.getByRole("button", { name: /Next/i });
    expect(nextBtn).toBeDisabled();

    // Add first member
    await user.click(screen.getByRole("button", { name: /Add Member/i }));
    
    // Fill display name and role
    const displayInputs = screen.getAllByPlaceholderText("Agent Name");
    expect(displayInputs).toHaveLength(1);
    await user.type(displayInputs[0], "Lead Designer");

    const roleInputs = screen.getAllByPlaceholderText("Role (e.g. Marketing, Engineering)");
    await user.type(roleInputs[0], "Design Lead");

    // Add second member and toggle type to Group
    await user.click(screen.getByRole("button", { name: /Add Member/i }));
    
    const memberCards = screen.getAllByRole("button", { name: "Group" });
    expect(memberCards).toHaveLength(2);
    // Click 'Group' button on the second member card to toggle type
    await user.click(memberCards[1]);

    // Select nested group
    const selects = await waitFor(() => {
      const el = container.querySelectorAll("select");
      if (el.length < 3) throw new Error("Selects not ready");
      return el;
    });
    await user.selectOptions(selects[1], "group1");

    // Assign agent for first member (Lead Designer)
    await user.selectOptions(selects[0], "agent1");

    // Next should be enabled since we have 2 members
    expect(nextBtn).toBeEnabled();

    // Click Next to review step
    await user.click(nextBtn);
    expect(screen.getByText("Review")).toHaveClass("bg-primary");
  });

  it("renders review summary and executes group creation successfully", async () => {
    const user = userEvent.setup();
    let mutationPayload: Record<string, unknown> | null = null;

    server.use(
      http.post("*/groupstore/groups", async ({ request }) => {
        mutationPayload = await request.json();
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "/groupstore/groups/new-group-123",
          },
        });
      })
    );

    const { container } = renderWithProviders(<CreateGroupDialog open={true} onClose={mockOnClose} />);

    // Load from template (Advisory Board has preconfigured roles)
    await user.click(screen.getByTestId("template-advisory-board"));
    await user.click(screen.getByRole("button", { name: /Next/i })); // basics
    
    // members step: assign agents
    const selects = await waitFor(() => {
      const el = container.querySelectorAll("select");
      if (el.length < 6) throw new Error("Selects not ready");
      return el;
    });
    // Selects are: 5 member selects + 1 moderator select
    await user.selectOptions(selects[0], "agent1");
    await user.selectOptions(selects[1], "agent2");
    await user.selectOptions(selects[2], "agent1");
    await user.selectOptions(selects[3], "agent2");
    await user.selectOptions(selects[4], "agent1");
    await user.selectOptions(selects[5], "agent1"); // Moderator

    // Go to review step
    await user.click(screen.getByRole("button", { name: /Next/i }));

    // Review step validations
    expect(screen.getByTestId("review-summary")).toBeInTheDocument();
    expect(screen.getByText("Advisory Board")).toBeInTheDocument();
    
    // Create group
    const createBtn = screen.getByRole("button", { name: "Create Group" });
    await user.click(createBtn);

    await waitFor(() => {
      expect(mutationPayload).not.toBeNull();
      expect(mutationPayload.name).toBe("Advisory Board");
      expect(mutationPayload.moderatorAgentId).toBe("agent1");
      expect(mutationPayload.members).toHaveLength(5);
      expect(mockOnClose).toHaveBeenCalled();
    });
  });

  it("shows warnings for unassigned members on review step and supports going back", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateGroupDialog open={true} onClose={mockOnClose} />);

    // Start blank and add members but don't assign agents
    await user.click(screen.getByTestId("template-blank"));
    await user.type(screen.getByTestId("group-name-input"), "Test Group");
    await user.click(screen.getByRole("button", { name: /Next/i }));

    await user.click(screen.getByRole("button", { name: /Add Member/i }));
    await user.type(screen.getAllByPlaceholderText("Agent Name")[0], "Member A");
    await user.click(screen.getByRole("button", { name: /Add Member/i }));
    await user.type(screen.getAllByPlaceholderText("Agent Name")[1], "Member B");

    // Click Next
    await user.click(screen.getByRole("button", { name: /Next/i }));

    // Review step should show warning
    expect(screen.getByText(/2 member\(s\) unassigned/)).toBeInTheDocument();
    expect(screen.getAllByText("Unassigned")).toHaveLength(2);

    // Clicking Back should return to members step
    const backBtn = screen.getByRole("button", { name: /Back/i });
    await user.click(backBtn);
    expect(screen.getByText("Members")).toHaveClass("bg-primary");
  });
});
