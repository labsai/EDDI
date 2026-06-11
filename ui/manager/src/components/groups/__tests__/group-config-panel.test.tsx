import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GroupConfigPanel } from "../group-config-panel";
import type { AgentGroupConfiguration } from "@/lib/api/groups";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

const mockConfig: AgentGroupConfiguration = {
  name: "Product Design Council",
  description: "Discusses product design and user experience aspects.",
  style: "ROUND_TABLE",
  maxRounds: 3,
  moderatorAgentId: "agent-mod",
  members: [
    {
      agentId: "agent-mod",
      displayName: "Moderator Agent",
      speakingOrder: 0,
      role: "Moderator",
      memberType: "AGENT",
    },
    {
      agentId: "agent-peer",
      displayName: "Peer Reviewer Agent",
      speakingOrder: 1,
      role: "Reviewer",
      memberType: "AGENT",
    },
    {
      agentId: "agent-group-sub",
      displayName: "Nested Council Group",
      speakingOrder: 2,
      role: "Subgroup",
      memberType: "GROUP",
    },
  ],
  phases: null,
  protocol: {
    agentTimeoutSeconds: 30,
    onAgentFailure: "SKIP",
    maxRetries: 2,
    onMemberUnavailable: "SKIP",
  },
};

describe("GroupConfigPanel", () => {
  it("renders group configuration metadata correctly", () => {
    renderWithProviders(<GroupConfigPanel config={mockConfig} />);

    expect(screen.getByText("Product Design Council")).toBeInTheDocument();
    expect(screen.getByText("Discusses product design and user experience aspects.")).toBeInTheDocument();
    expect(screen.getByText("Round Table")).toBeInTheDocument();
    expect(screen.getByText("Opinion")).toBeInTheDocument();
    expect(screen.getByText("Discussion")).toBeInTheDocument();
    expect(screen.getByText("Synthesis")).toBeInTheDocument();
  });

  it("renders members list with initials, role badges, and moderator highlight", () => {
    renderWithProviders(<GroupConfigPanel config={mockConfig} />);

    expect(screen.getByText("Moderator Agent")).toBeInTheDocument();
    expect(screen.getByText("Peer Reviewer Agent")).toBeInTheDocument();
    expect(screen.getByText("Nested Council Group")).toBeInTheDocument();

    // Check initials
    expect(screen.getByText("MA")).toBeInTheDocument();
    expect(screen.getByText("PR")).toBeInTheDocument();
    expect(screen.getByText("NC")).toBeInTheDocument();

    // Badges
    expect(screen.getByText("Moderator")).toBeInTheDocument();
    expect(screen.getByText("Reviewer")).toBeInTheDocument();
    expect(screen.getByText("Subgroup")).toBeInTheDocument();

    // Mod star badge
    expect(screen.getByText("⭐ Mod")).toBeInTheDocument();

    // Nested Group badge
    expect(screen.getByText("Group")).toBeInTheDocument();
  });

  it("renders protocol configuration details", () => {
    renderWithProviders(<GroupConfigPanel config={mockConfig} />);

    expect(screen.getByText("Timeout")).toBeInTheDocument();
    expect(screen.getByText("30s")).toBeInTheDocument();
    expect(screen.getByText("On Failure")).toBeInTheDocument();
    expect(screen.getAllByText("SKIP")).toHaveLength(2);
    expect(screen.getByText("Max Retries")).toBeInTheDocument();
    expect(screen.getByText("2")).toBeInTheDocument();
    expect(screen.getByText("Max Rounds")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("handles soft-delete workflow of the group and member agents", async () => {
    const user = userEvent.setup();

    const deletedAgents: string[] = [];
    let groupDeleted = false;

    server.use(
      http.get("*/agentstore/agents/:agentId/currentversion", () => {
        return HttpResponse.json(1);
      }),
      http.delete("*/agentstore/agents/:agentId", ({ params }) => {
        deletedAgents.push(params.agentId as string);
        return new HttpResponse(null, { status: 204 });
      }),
      http.delete("*/groupstore/groups/:groupId", ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get("permanent")).toBe("false");
        expect(url.searchParams.get("version")).toBe("1");
        groupDeleted = true;
        return new HttpResponse(null, { status: 204 });
      })
    );

    renderWithProviders(
      <GroupConfigPanel config={mockConfig} groupId="grp-1" groupVersion={1} />
    );

    const deleteBtn = screen.getByText("Delete Group + All Agents");
    expect(deleteBtn).toBeInTheDocument();

    await user.click(deleteBtn);

    // Warning should show up
    expect(screen.getByText(/This will soft-delete the group/)).toBeInTheDocument();

    // Cancel deletes
    const cancelBtn = screen.getByRole("button", { name: "Cancel" });
    await user.click(cancelBtn);

    expect(screen.queryByText(/This will soft-delete the group/)).not.toBeInTheDocument();

    // Open again and confirm deletion
    const deleteBtnFresh = screen.getByRole("button", { name: "Delete Group + All Agents" });
    await user.click(deleteBtnFresh);
    const confirmBtn = screen.getByRole("button", { name: "Confirm" });
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(groupDeleted).toBe(true);
      // It should delete both agent-mod and agent-peer (but not agent-group-sub as memberType is GROUP)
      expect(deletedAgents).toContain("agent-mod");
      expect(deletedAgents).toContain("agent-peer");
      expect(deletedAgents).not.toContain("agent-group-sub");
    });
  });
});
