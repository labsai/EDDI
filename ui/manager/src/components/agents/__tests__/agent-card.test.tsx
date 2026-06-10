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
  lastModifiedOn: new Date().toISOString(),
  createdOn: new Date().toISOString(),
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
      expect(screen.getByText("Deploy")).toBeInTheDocument();
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
      expect(screen.getByText("Undeploy")).toBeInTheDocument();
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
        screen.getByTestId("agent-chat-agent-test-1")
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
        "agent-external-chat-agent-test-1"
      );
      expect(extLink).toHaveAttribute(
        "href",
        "/chat/production/agent-test-1"
      );
    });
  });
});
