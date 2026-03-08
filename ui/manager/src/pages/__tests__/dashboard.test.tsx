import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DashboardPage } from "@/pages/dashboard";

describe("DashboardPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<DashboardPage />);
    // i18n key renders as-is when translation is missing (fallback to key)
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("renders all four stat cards", () => {
    renderWithProviders(<DashboardPage />);
    expect(screen.getByText("Active Bots")).toBeInTheDocument();
    expect(screen.getByText("Conversations Today")).toBeInTheDocument();
    expect(screen.getByText("Avg Response Time")).toBeInTheDocument();
    expect(screen.getByText("Total Cost")).toBeInTheDocument();
  });

  it("shows placeholder values for stats", () => {
    renderWithProviders(<DashboardPage />);
    const dashes = screen.getAllByText("—");
    expect(dashes).toHaveLength(4);
  });
});
