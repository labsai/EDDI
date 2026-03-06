import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
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

  it("has hidden file input for zip import", () => {
    renderWithProviders(<BotsPage />);
    const input = screen.getByTestId("import-file-input") as HTMLInputElement;
    expect(input).toBeInTheDocument();
    expect(input.type).toBe("file");
    expect(input.accept).toBe(".zip");
  });

  it("still renders create bot button", () => {
    renderWithProviders(<BotsPage />);
    expect(screen.getByTestId("create-bot-btn")).toBeInTheDocument();
  });
});
