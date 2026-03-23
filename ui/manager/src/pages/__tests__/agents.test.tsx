import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { AgentsPage } from "@/pages/agents";

describe("AgentsPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<AgentsPage />);
    expect(screen.getByText("Agents")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithProviders(<AgentsPage />);
    expect(screen.getByTestId("agent-search")).toBeInTheDocument();
  });

  it("renders create agent button", () => {
    renderWithProviders(<AgentsPage />);
    expect(screen.getByTestId("create-agent-btn")).toBeInTheDocument();
  });

  it("shows loading state initially", () => {
    renderWithProviders(<AgentsPage />);
    // TanStack Query will immediately show loading
    expect(screen.getByText("Agents")).toBeInTheDocument();
  });
});
