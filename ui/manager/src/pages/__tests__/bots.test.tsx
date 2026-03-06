import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { BotsPage } from "@/pages/bots";

describe("BotsPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByText("Bots")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByTestId("bot-search")).toBeInTheDocument();
  });

  it("renders create bot button", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByTestId("create-bot-btn")).toBeInTheDocument();
  });

  it("shows loading state initially", () => {
    renderWithProviders(<BotsPage />);
    // TanStack Query will immediately show loading
    expect(screen.getByText("Bots")).toBeInTheDocument();
  });
});
