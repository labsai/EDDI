import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DashboardPage } from "@/pages/dashboard";

// Mock the dashboard hooks since they need API calls
vi.mock("@/hooks/use-dashboard", () => ({
  useDashboardStats: () => ({
    data: { agentCount: 5, workflowCount: 3, conversationCount: 42, resourceCount: 0 },
    isLoading: false,
  }),
  useRecentAgents: () => ({
    data: [],
    isLoading: false,
  }),
  useRecentConversations: () => ({
    data: [],
    isLoading: false,
  }),
  useCoordinatorStatusLight: () => ({
    data: { coordinatorType: "nats", connected: true, connectionStatus: "CONNECTED", totalProcessed: 100, totalDeadLettered: 0, queueDepths: {}, activeConversations: 2 },
    isLoading: false,
  }),
}));

vi.mock("@/hooks/use-agents", () => ({
  groupAgentsByName: () => [],
}));

vi.mock("@/hooks/use-platform-status", () => ({
  usePlatformStatus: () => ({
    status: "online",
    instanceId: "test-instance",
    latencyMs: 15,
    lastCheckedAt: new Date(),
  }),
}));

vi.mock("@/hooks/use-secrets", () => ({
  useVaultHealth: () => ({
    data: { status: "UP", provider: "hashicorp", available: true },
    isLoading: false,
  }),
  useSecrets: () => ({ data: [], isLoading: false }),
}));

describe("DashboardPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("renders stat cards with real data", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });

  it("renders quick action buttons", () => {
    renderWithProviders(<DashboardPage />);
    // Quick actions section exists
    expect(screen.getByText("Agent Wizard")).toBeInTheDocument();
  });

  it("renders platform health strip", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByTestId("platform-health-strip")).toBeInTheDocument();
    expect(screen.getByText("Online")).toBeInTheDocument();
  });

  it("renders expanded quick actions", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("View Logs")).toBeInTheDocument();
    expect(screen.getByText("Orphan Scan")).toBeInTheDocument();
    expect(screen.getByText("Audit Trail")).toBeInTheDocument();
  });
});
