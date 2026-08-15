import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AgentCard } from "@/components/agents/agent-card";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

const mockAgent = {
  id: "agent-test-1",
  version: 1,
  resource: "eddi://ai.labs.agent/agentstore/agents/agent-test-1?version=1",
  name: "Test Agent",
  description: "A test agent for unit testing",
  lastModifiedOn: Date.now(),
  createdOn: Date.now() - 86400000,
};

describe("AgentCard", () => {
  const defaultProps = {
    agent: mockAgent,
    onDuplicate: vi.fn(),
    onDelete: vi.fn(),
    onExport: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders agent name and description", () => {
    renderWithProviders(<AgentCard {...defaultProps} />);
    expect(screen.getByText("Test Agent")).toBeInTheDocument();
    expect(
      screen.getByText("A test agent for unit testing")
    ).toBeInTheDocument();
  });

  it("renders agent ID", () => {
    renderWithProviders(<AgentCard {...defaultProps} />);
    expect(screen.getByText("agent-test-1")).toBeInTheDocument();
  });

  it("renders data-testid with agent id", () => {
    renderWithProviders(<AgentCard {...defaultProps} />);
    expect(
      screen.getByTestId("agent-card-agent-test-1")
    ).toBeInTheDocument();
  });

  it("renders 'Unnamed Agent' when name is empty", () => {
    renderWithProviders(
      <AgentCard
        {...defaultProps}
        agent={{ ...mockAgent, name: "" }}
      />
    );
    expect(screen.getByText("Unnamed Agent")).toBeInTheDocument();
  });

  it("renders 'No description' when description is empty", () => {
    renderWithProviders(
      <AgentCard
        {...defaultProps}
        agent={{ ...mockAgent, description: "" }}
      />
    );
    expect(screen.getByText("No description")).toBeInTheDocument();
  });

  it("shows deploy button when status is NOT_FOUND", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({});
      })
    );
    renderWithProviders(<AgentCard {...defaultProps} />);
    await waitFor(() => {
      // The toggle names its environment now: a bare "Deploy" beside a "Test"
      // chip was exactly the ambiguity this card change removes.
      expect(screen.getByTestId("agent-deploy-toggle-agent-test-1")).toHaveTextContent(
        "Deploy to production",
      );
    });
  });

  it("shows undeploy button when status is READY", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "READY" });
      })
    );
    renderWithProviders(<AgentCard {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByTestId("agent-deploy-toggle-agent-test-1")).toHaveTextContent(
        "Undeploy from production",
      );
    });
  });

  it("shows chat buttons when deployed", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "READY" });
      })
    );
    renderWithProviders(<AgentCard {...defaultProps} />);
    await waitFor(() => {
      expect(
        screen.getByTestId("agent-chat-production-agent-test-1")
      ).toBeInTheDocument();
    });
  });

  it("opens context menu when more actions clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentCard {...defaultProps} />);
    const menuBtn = screen.getByTestId("agent-menu-agent-test-1");
    await user.click(menuBtn);
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    expect(screen.getByText("Export")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  it("calls onDuplicate when duplicate is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentCard {...defaultProps} />);
    await user.click(screen.getByTestId("agent-menu-agent-test-1"));
    await user.click(screen.getByText("Duplicate"));
    expect(defaultProps.onDuplicate).toHaveBeenCalledWith(
      "agent-test-1",
      1
    );
  });

  it("calls onDelete when delete is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentCard {...defaultProps} />);
    await user.click(screen.getByTestId("agent-menu-agent-test-1"));
    await user.click(screen.getByText("Delete"));
    expect(defaultProps.onDelete).toHaveBeenCalledWith("agent-test-1", 1);
  });

  it("calls onExport when export is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AgentCard {...defaultProps} />);
    await user.click(screen.getByTestId("agent-menu-agent-test-1"));
    await user.click(screen.getByText("Export"));
    expect(defaultProps.onExport).toHaveBeenCalledWith("agent-test-1", 1);
  });

  it("has a link to agent detail page", () => {
    renderWithProviders(<AgentCard {...defaultProps} />);
    const link = screen.getByRole("link", { name: /Test Agent/ });
    expect(link).toHaveAttribute("href", "/manage/agentview/agent-test-1");
  });

  it("shows external chat link when deployed", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        return HttpResponse.json({ status: "READY" });
      })
    );
    renderWithProviders(<AgentCard {...defaultProps} />);
    await waitFor(() => {
      const extLink = screen.getByTestId(
        "agent-external-chat-production-agent-test-1"
      );
      expect(extLink).toHaveAttribute(
        "href",
        "/chat/production/agent-test-1"
      );
    });
  });

  /**
   * The bug the whole change exists for. This card used to read deployment
   * status for production ONLY, so a test-deployed agent was labelled "Not
   * deployed" and offered no chat button at all — it was invisible and
   * unreachable, and the user's conclusion was that the agent was broken.
   */
  it("shows a test-only agent as live in Test, and offers a Test chat", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", ({ params }) =>
        HttpResponse.json({ status: params.env === "test" ? "READY" : "NOT_FOUND" }),
      ),
    );
    renderWithProviders(<AgentCard {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByTestId("env-chip-test")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("env-chip-production")).not.toBeInTheDocument();
    expect(screen.queryByText("Not deployed")).not.toBeInTheDocument();

    // Reachable, and pointed at the environment it actually runs in.
    expect(screen.getByTestId("agent-chat-test-agent-test-1")).toBeInTheDocument();
    expect(
      screen.getByTestId("agent-external-chat-test-agent-test-1"),
    ).toHaveAttribute("href", "/chat/test/agent-test-1");
    // ...and the production toggle still offers to deploy there.
    expect(screen.getByText("Deploy to production")).toBeInTheDocument();
  });

  it("names both environments when the agent is live in both", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", () =>
        HttpResponse.json({ status: "READY" }),
      ),
    );
    renderWithProviders(<AgentCard {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByTestId("env-chip-production")).toBeInTheDocument();
    });
    expect(screen.getByTestId("env-chip-test")).toBeInTheDocument();
    // One chat entry each — with two environments the label must disambiguate.
    expect(screen.getByTestId("agent-chat-production-agent-test-1")).toBeInTheDocument();
    expect(screen.getByTestId("agent-chat-test-agent-test-1")).toBeInTheDocument();
  });

  /**
   * A failed deployment must stay visible when another environment is healthy.
   * Rendering only the green chip would hide a broken production deploy behind
   * a working test one — the same omission, one level down, that this whole
   * component exists to fix.
   */
  it("surfaces an errored environment alongside a live one", async () => {
    server.use(
      http.get("*/administration/:env/deploymentstatus/:agentId", ({ params }) =>
        HttpResponse.json({ status: params.env === "test" ? "READY" : "ERROR" }),
      ),
    );
    renderWithProviders(<AgentCard {...defaultProps} />);

    await waitFor(() => {
      expect(screen.getByTestId("env-chip-test")).toBeInTheDocument();
    });
    expect(screen.getByTestId("env-chip-error-production")).toBeInTheDocument();
  });
});
