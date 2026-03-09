import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DashboardPage } from "@/pages/dashboard";

// Mock the dashboard hooks since they need API calls
vi.mock("@/hooks/use-dashboard", () => ({
  useDashboardStats: () => ({
    data: { botCount: 5, packageCount: 3, conversationCount: 42, resourceCount: 0 },
    isLoading: false,
  }),
  useRecentBots: () => ({
    data: [],
    isLoading: false,
  }),
}));

vi.mock("@/hooks/use-bots", () => ({
  groupBotsByName: () => [],
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
    expect(screen.getByText("Bot Wizard")).toBeInTheDocument();
  });
});
