import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { BotsPage } from "@/pages/bots";

describe("BotsPage — Import/Export", () => {
  it("renders import bot button", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByTestId("import-bot-btn")).toBeInTheDocument();
  });

  it("renders bot wizard button", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByTestId("bot-wizard-btn")).toBeInTheDocument();
  });

  it("import button opens import dialog on click", async () => {
    renderWithProviders(<BotsPage />);
    const user = userEvent.setup();
    const btn = screen.getByTestId("import-bot-btn");
    await user.click(btn);
    expect(screen.getByTestId("import-bot-dialog")).toBeInTheDocument();
  });

  it("still renders create bot button", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByTestId("create-bot-btn")).toBeInTheDocument();
  });
});
