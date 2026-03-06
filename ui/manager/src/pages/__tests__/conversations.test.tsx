import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ConversationsPage } from "@/pages/conversations";

describe("ConversationsPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<ConversationsPage />);
    expect(screen.getByText("Conversations")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithProviders(<ConversationsPage />);
    expect(screen.getByTestId("conversation-search")).toBeInTheDocument();
  });

  it("renders state filter pills", () => {
    renderWithProviders(<ConversationsPage />);
    expect(screen.getByText("All")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Ended")).toBeInTheDocument();
    expect(screen.getByText("Error")).toBeInTheDocument();
  });

  it("shows subtitle text", () => {
    renderWithProviders(<ConversationsPage />);
    expect(
      screen.getByText("View and manage bot conversations")
    ).toBeInTheDocument();
  });
});
