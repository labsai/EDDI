import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AgentsPage } from "@/pages/agents";

describe("AgentsPage — Import/Export", () => {
  it("renders import agent button", () => {
    renderWithProviders(<AgentsPage />);
    expect(screen.getByTestId("import-agent-btn")).toBeInTheDocument();
  });

  it("renders create-agent-btn that opens the choice dialog", async () => {
    renderWithProviders(<AgentsPage />);
    const user = userEvent.setup();
    const btn = screen.getByTestId("create-agent-btn");
    expect(btn).toHaveTextContent("New Agent");
    await user.click(btn);
    expect(screen.getByTestId("create-or-wizard-dialog")).toBeInTheDocument();
  });

  it("import button opens import dialog on click", async () => {
    renderWithProviders(<AgentsPage />);
    const user = userEvent.setup();
    const btn = screen.getByTestId("import-agent-btn");
    await user.click(btn);
    expect(screen.getByTestId("import-agent-dialog")).toBeInTheDocument();
  });

  it("still renders create agent button", () => {
    renderWithProviders(<AgentsPage />);
    expect(screen.getByTestId("create-agent-btn")).toBeInTheDocument();
  });
});
