import { describe, it, expect, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DashboardPage } from "@/pages/dashboard";

// ─── Dynamic mocks ────────────────────────────────────────────────────────

const mockUseDashboardStats = vi.fn();
const mockUseRecentAgents = vi.fn();
const mockUseRecentConversations = vi.fn();
const mockUseCoordinatorStatusLight = vi.fn();

vi.mock("@/hooks/use-dashboard", () => ({
  useDashboardStats: (...args: unknown[]) => mockUseDashboardStats(...args),
  useRecentAgents: (...args: unknown[]) => mockUseRecentAgents(...args),
  useRecentConversations: (...args: unknown[]) => mockUseRecentConversations(...args),
  useCoordinatorStatusLight: (...args: unknown[]) => mockUseCoordinatorStatusLight(...args),
}));

const mockUsePlatformStatus = vi.fn();
vi.mock("@/hooks/use-platform-status", () => ({
  usePlatformStatus: (...args: unknown[]) => mockUsePlatformStatus(...args),
}));

const mockUseVaultHealth = vi.fn();
vi.mock("@/hooks/use-secrets", () => ({
  useVaultHealth: (...args: unknown[]) => mockUseVaultHealth(...args),
  useSecrets: () => ({ data: [], isLoading: false }),
}));

vi.mock("@/hooks/use-agents", () => ({
  useAgentDescriptors: () => ({
    data: [
      { resource: "eddi://ai.labs.bot/botstore/bots/agent-1?version=1", name: "Support Agent" },
      { resource: "eddi://ai.labs.bot/botstore/bots/agent-2?version=1", name: "FAQ Agent" },
    ],
  }),
  groupAgentsByName: (agents: unknown[]) =>
    (agents as { resource: string; name?: string; description?: string; lastModifiedOn?: string | null }[]).map((a) => {
      const match = /\/bots\/([^?]+)\?version=(\d+)/.exec(a.resource);
      return {
        id: match?.[1] ?? "unknown",
        version: Number(match?.[2] ?? 1),
        name: a.name,
        description: a.description,
        lastModifiedOn: a.lastModifiedOn,
      };
    }),
}));

// ─── Default mock data ──────────────────────────────────────────────────────

function setDefaultMocks() {
  mockUseDashboardStats.mockReturnValue({
    data: { agentCount: 5, workflowCount: 3, conversationCount: 42, resourceCount: 0 },
    isLoading: false,
  });
  mockUseRecentAgents.mockReturnValue({
    data: [
      {
        resource: "eddi://ai.labs.bot/botstore/bots/agent-1?version=1",
        name: "Support Agent",
        description: "Handles customer support",
        lastModifiedOn: new Date().toISOString(),
      },
      {
        resource: "eddi://ai.labs.bot/botstore/bots/agent-2?version=1",
        name: "FAQ Agent",
        description: null,
        lastModifiedOn: null,
      },
    ],
    isLoading: false,
  });
  mockUseRecentConversations.mockReturnValue({
    data: [
      {
        resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv-1",
        agentId: "agent-1",
        agentVersion: 2,
        name: "Customer chat",
        conversationState: "READY",
        lastModifiedOn: new Date().toISOString(),
      },
      {
        resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv-2",
        agentId: "agent-2",
        agentVersion: 1,
        name: null,
        conversationState: "IN_PROGRESS",
        lastModifiedOn: null,
      },
      {
        resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv-3",
        agentId: "agent-3",
        agentVersion: 1,
        name: null,
        conversationState: "ERROR",
        lastModifiedOn: new Date().toISOString(),
      },
      {
        resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv-4",
        agentId: "agent-1",
        agentVersion: 2,
        name: null,
        conversationState: "ENDED",
        lastModifiedOn: new Date().toISOString(),
      },
    ],
    isLoading: false,
  });
  mockUseCoordinatorStatusLight.mockReturnValue({
    data: { coordinatorType: "nats", connected: true, connectionStatus: "CONNECTED", totalProcessed: 100, totalDeadLettered: 0, queueDepths: {}, activeConversations: 2 },
    isLoading: false,
  });
  mockUsePlatformStatus.mockReturnValue({
    status: "online",
    instanceId: "test-instance",
    latencyMs: 15,
    lastCheckedAt: new Date(),
  });
  mockUseVaultHealth.mockReturnValue({
    data: { status: "UP", provider: "hashicorp", available: true },
    isLoading: false,
  });
}

describe("DashboardPage", () => {
  beforeEach(() => {
    setDefaultMocks();
  });

  // ─── Page structure ─────────────────────────────────────────────────────

  it("renders page heading", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("renders page subtitle", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByRole("heading", { level: 1 }).parentElement?.querySelector("p")).toBeInTheDocument();
  });

  it("renders stat cards with real data", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });

  it("renders exactly 3 visible stat cards when resource count is 0", () => {
    renderWithProviders(<DashboardPage />);
    const agentLink = screen.getByLabelText(/: 5$/);
    const workflowLink = screen.getByLabelText(/: 3$/);
    const convLink = screen.getByLabelText(/: 42$/);
    expect(agentLink).toBeInTheDocument();
    expect(workflowLink).toBeInTheDocument();
    expect(convLink).toBeInTheDocument();
    expect(screen.queryByLabelText(/: 0$/)).not.toBeInTheDocument();
  });

  // ─── Quick actions ──────────────────────────────────────────────────────

  it("renders quick action buttons", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Agent Wizard")).toBeInTheDocument();
  });

  it("renders expanded quick actions", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("View Logs")).toBeInTheDocument();
    expect(screen.getByText("Audit Trail")).toBeInTheDocument();
    expect(screen.getByText("Secret Vault")).toBeInTheDocument();
    expect(screen.getByText("Create Group")).toBeInTheDocument();
  });

  it("quick action links navigate to correct routes", () => {
    renderWithProviders(<DashboardPage />);
    const wizardLink = screen.getByText("Agent Wizard").closest("a");
    expect(wizardLink).toHaveAttribute("href", "/manage/agents/wizard");

    const logsLink = screen.getByText("View Logs").closest("a");
    expect(logsLink).toHaveAttribute("href", "/manage/logs");

    const auditLink = screen.getByText("Audit Trail").closest("a");
    expect(auditLink).toHaveAttribute("href", "/manage/audit");

    const vaultLink = screen.getByText("Secret Vault").closest("a");
    expect(vaultLink).toHaveAttribute("href", "/manage/secrets");

    const groupLink = screen.getByText("Create Group").closest("a");
    expect(groupLink).toHaveAttribute("href", "/manage/groups/wizard");
  });

  // ─── Platform health strip ──────────────────────────────────────────────

  it("renders platform health strip", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByTestId("platform-health-strip")).toBeInTheDocument();
    expect(screen.getByText("Online")).toBeInTheDocument();
  });

  it("shows coordinator connected status", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Coordinator connected")).toBeInTheDocument();
  });

  it("shows vault ready status", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Vault ready")).toBeInTheDocument();
  });

  // ─── Recent conversations ──────────────────────────────────────────────

  it("renders recent conversations section", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Recent Conversations")).toBeInTheDocument();
    expect(screen.getByTestId("recent-conversations")).toBeInTheDocument();
  });

  it("shows conversation names or agent names", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Customer chat")).toBeInTheDocument();
  });

  it("shows conversation state badges", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("READY")).toBeInTheDocument();
    expect(screen.getByText("IN_PROGRESS")).toBeInTheDocument();
    expect(screen.getByText("ERROR")).toBeInTheDocument();
  });

  it("shows ENDED conversation state badge", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("ENDED")).toBeInTheDocument();
  });

  it("conversations link to conversation view", () => {
    renderWithProviders(<DashboardPage />);
    const recentConvSection = screen.getByTestId("recent-conversations");
    const conversationLinks = within(recentConvSection).getAllByRole("link");
    expect(conversationLinks.length).toBe(4);
    expect(conversationLinks[0]).toHaveAttribute("href", expect.stringContaining("/manage/conversationview/"));
  });

  // ─── Recent agents ────────────────────────────────────────────────────

  it("renders recent agents section", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByTestId("recent-agents")).toBeInTheDocument();
  });

  it("shows recent agent names", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getAllByText("Support Agent").length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText("FAQ Agent").length).toBeGreaterThanOrEqual(1);
  });

  it("shows agent description or dash for no description", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Handles customer support")).toBeInTheDocument();
    const dashes = screen.getAllByText("—");
    expect(dashes.length).toBeGreaterThanOrEqual(1);
  });

  it("agent cards link to agent view", () => {
    renderWithProviders(<DashboardPage />);
    const agentSection = screen.getByTestId("recent-agents");
    const agentLinks = agentSection.querySelectorAll("a[href*='/manage/agentview/']");
    expect(agentLinks.length).toBeGreaterThanOrEqual(1);
  });

  it("shows 'view all agents' link", () => {
    renderWithProviders(<DashboardPage />);
    const viewAllLink = screen.getByTestId("recent-agents").querySelector("a[href='/manage/agents']");
    expect(viewAllLink).toBeInTheDocument();
  });

  it("shows 'view all conversations' link", () => {
    renderWithProviders(<DashboardPage />);
    const viewAllLink = screen.getByText("Recent Conversations")
      .closest("div")
      ?.querySelector("a[href='/manage/conversations']");
    expect(viewAllLink).toBeInTheDocument();
  });

  // ─── Conversation display name fallback ─────────────────────────────────

  it("shows 'Unnamed Agent' when conv has no name and agent not in map", () => {
    renderWithProviders(<DashboardPage />);
    const unnamedElements = screen.getAllByText("Unnamed Agent");
    expect(unnamedElements.length).toBeGreaterThanOrEqual(1);
  });

  it("falls back to agent name from agentNameMap when conv.name is null", () => {
    renderWithProviders(<DashboardPage />);
    const faqElements = screen.getAllByText("FAQ Agent");
    expect(faqElements.length).toBeGreaterThanOrEqual(1);
  });

  // ─── Conversation lastModifiedOn null ──────────────────────────────────

  it("shows dash for conversation with no lastModifiedOn", () => {
    renderWithProviders(<DashboardPage />);
    const dashes = screen.getAllByText("—");
    expect(dashes.length).toBeGreaterThanOrEqual(1);
  });

  // ─── Chat quick action ────────────────────────────────────────────────

  it("renders chat quick action with correct link", () => {
    renderWithProviders(<DashboardPage />);
    const chatLink = screen.getByText("Chat").closest("a");
    expect(chatLink).toHaveAttribute("href", "/manage/chat");
  });

  // ─── Stat card aria-labels ────────────────────────────────────────────

  it("stat card links have aria-labels with label and value", () => {
    renderWithProviders(<DashboardPage />);
    const statLinks = document.querySelectorAll("a[aria-label]");
    expect(statLinks.length).toBeGreaterThanOrEqual(3);
  });

  // ─── Stat card non-zero values display ────────────────────────────────

  it("stat cards show correct non-zero values", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });

  // ─── Agent card lastModifiedOn null ────────────────────────────────────

  it("shows FAQ Agent in recent agents section even with null lastModifiedOn", () => {
    renderWithProviders(<DashboardPage />);
    const agentSection = screen.getByTestId("recent-agents");
    expect(within(agentSection).getByText("FAQ Agent")).toBeInTheDocument();
  });

  // ─── Stat card links navigate to correct pages ────────────────────────

  it("stat card links navigate to correct pages", () => {
    renderWithProviders(<DashboardPage />);
    const agentLink = screen.getByLabelText(/: 5$/);
    expect(agentLink).toHaveAttribute("href", "/manage/agents");

    const wfLink = screen.getByLabelText(/: 3$/);
    expect(wfLink).toHaveAttribute("href", "/manage/workflows");

    const convLink = screen.getByLabelText(/: 42$/);
    expect(convLink).toHaveAttribute("href", "/manage/conversations");
  });

  // ─── STATE_COLORS coverage — ENDED state ────────────────────────────────

  it("renders ENDED state badge with correct styling class", () => {
    renderWithProviders(<DashboardPage />);
    const endedBadge = screen.getByText("ENDED");
    expect(endedBadge).toBeInTheDocument();
    expect(endedBadge.className).toContain("bg-gray-500");
  });

  // ─── Agent section shows description fallback ─────────────────────────

  it("agent card shows description text when available", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Handles customer support")).toBeInTheDocument();
  });

  // ─── Conversation version display ──────────────────────────────────────

  it("shows agent version in conversation card", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getAllByText(/v2/).length).toBeGreaterThanOrEqual(1);
  });

  // ─── Conversation card shows truncated ID ──────────────────────────────

  it("shows truncated conversation ID in card", () => {
    renderWithProviders(<DashboardPage />);
    const recentConvSection = screen.getByTestId("recent-conversations");
    expect(within(recentConvSection).getAllByText(/…/).length).toBeGreaterThanOrEqual(1);
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Loading states
  // ═══════════════════════════════════════════════════════════════════════

  it("shows skeleton cards when stats are loading", () => {
    mockUseDashboardStats.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderWithProviders(<DashboardPage />);
    // When stats loading, 4 skeleton cards are rendered
    const skeletons = document.querySelectorAll(".animate-pulse");
    expect(skeletons.length).toBeGreaterThan(0);
    // No stat card links should appear
    expect(screen.queryByLabelText(/: \d+$/)).not.toBeInTheDocument();
  });

  it("shows skeleton cards when conversations are loading", () => {
    mockUseRecentConversations.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderWithProviders(<DashboardPage />);
    // Conversations section should show skeleton placeholders
    const skeletons = document.querySelectorAll(".animate-pulse");
    expect(skeletons.length).toBeGreaterThan(0);
    expect(screen.queryByTestId("recent-conversations")).not.toBeInTheDocument();
  });

  it("shows skeleton cards when agents are loading", () => {
    mockUseRecentAgents.mockReturnValue({
      data: undefined,
      isLoading: true,
    });
    renderWithProviders(<DashboardPage />);
    // Agent section should show skeleton placeholders
    const agentSection = screen.getByTestId("recent-agents");
    const skeletons = agentSection.querySelectorAll(".animate-pulse");
    expect(skeletons.length).toBeGreaterThan(0);
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Empty states
  // ═══════════════════════════════════════════════════════════════════════

  it("shows empty conversations message when no conversations exist", () => {
    mockUseRecentConversations.mockReturnValue({
      data: [],
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("No conversations yet")).toBeInTheDocument();
    expect(screen.queryByTestId("recent-conversations")).not.toBeInTheDocument();
  });

  it("shows empty conversations message when data is null", () => {
    mockUseRecentConversations.mockReturnValue({
      data: null,
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("No conversations yet")).toBeInTheDocument();
  });

  it("shows empty agents state with create button when no agents", () => {
    mockUseRecentAgents.mockReturnValue({
      data: [],
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    const agentSection = screen.getByTestId("recent-agents");
    // Should show the "no recent agents" message and a create button
    const createLink = agentSection.querySelector("a[href='/manage/agents/wizard']");
    expect(createLink).toBeInTheDocument();
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Platform health status variants
  // ═══════════════════════════════════════════════════════════════════════

  it("shows Offline when platform status is offline", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "offline",
      instanceId: "test-instance",
      latencyMs: 0,
      lastCheckedAt: new Date(),
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Offline")).toBeInTheDocument();
  });

  it("shows Checking when platform status is checking", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "checking",
      instanceId: null,
      latencyMs: 0,
      lastCheckedAt: null,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Checking…")).toBeInTheDocument();
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Coordinator status variants
  // ═══════════════════════════════════════════════════════════════════════

  it("shows coordinator disconnected when coordinator is not connected", () => {
    mockUseCoordinatorStatusLight.mockReturnValue({
      data: { coordinatorType: "nats", connected: false, connectionStatus: "DISCONNECTED", totalProcessed: 0, totalDeadLettered: 0, queueDepths: {}, activeConversations: 0 },
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Coordinator disconnected")).toBeInTheDocument();
  });

  it("shows in-memory coordinator icon when type is inMemory", () => {
    mockUseCoordinatorStatusLight.mockReturnValue({
      data: { coordinatorType: "inMemory", connected: true, connectionStatus: "CONNECTED", totalProcessed: 50, totalDeadLettered: 0, queueDepths: {}, activeConversations: 0 },
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Coordinator connected")).toBeInTheDocument();
  });

  it("shows fallback coordinator status when coordinator data is null", () => {
    mockUseCoordinatorStatusLight.mockReturnValue({
      data: null,
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Coordinator —")).toBeInTheDocument();
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Vault health variants
  // ═══════════════════════════════════════════════════════════════════════

  it("shows vault unavailable when vault health is down", () => {
    mockUseVaultHealth.mockReturnValue({
      data: { status: "DOWN", provider: "hashicorp", available: false },
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Vault unavailable")).toBeInTheDocument();
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Resource stat card visible when non-zero
  // ═══════════════════════════════════════════════════════════════════════

  it("shows resource stat card when resourceCount > 0", () => {
    mockUseDashboardStats.mockReturnValue({
      data: { agentCount: 5, workflowCount: 3, conversationCount: 42, resourceCount: 10 },
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    // All 4 stat cards should be visible
    expect(screen.getByText("10")).toBeInTheDocument();
    const statLinks = document.querySelectorAll("a[aria-label]");
    expect(statLinks.length).toBeGreaterThanOrEqual(4);
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Unknown conversation state fallback
  // ═══════════════════════════════════════════════════════════════════════

  it("renders unknown conversation state with fallback ENDED colors", () => {
    mockUseRecentConversations.mockReturnValue({
      data: [
        {
          resource: "eddi://ai.labs.conversation/conversationstore/conversations/conv-x",
          agentId: "agent-1",
          agentVersion: 1,
          name: "Unknown state conv",
          conversationState: "UNKNOWN_STATE",
          lastModifiedOn: new Date().toISOString(),
        },
      ],
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    const badge = screen.getByText("UNKNOWN_STATE");
    expect(badge).toBeInTheDocument();
    // Falls back to STATE_COLORS.ENDED which uses bg-gray-500
    expect(badge.className).toContain("bg-gray-500");
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Agent with null name
  // ═══════════════════════════════════════════════════════════════════════

  it("shows Unnamed Agent for agent card with null name", () => {
    mockUseRecentAgents.mockReturnValue({
      data: [
        {
          resource: "eddi://ai.labs.bot/botstore/bots/agent-x?version=1",
          name: null,
          description: "A mysterious agent",
          lastModifiedOn: new Date().toISOString(),
        },
      ],
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    const agentSection = screen.getByTestId("recent-agents");
    expect(within(agentSection).getByText("Unnamed Agent")).toBeInTheDocument();
  });

  // ═══════════════════════════════════════════════════════════════════════
  // Branch coverage: Stats data null (not loading but null)
  // ═══════════════════════════════════════════════════════════════════════

  it("shows zero stat values with Plus icon when stats data is null", () => {
    mockUseDashboardStats.mockReturnValue({
      data: null,
      isLoading: false,
    });
    renderWithProviders(<DashboardPage />);
    // All values default to 0 via ?? 0, and visibleStatCards filters out resources (0),
    // so 3 cards remain, each showing 0 with a Plus icon
    const statLinks = document.querySelectorAll("a[aria-label]");
    expect(statLinks.length).toBe(3);
  });
});
