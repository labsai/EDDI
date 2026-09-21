import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
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

  it("import dialog shows drop zone for file upload", async () => {
    renderWithProviders(<AgentsPage />);
    const user = userEvent.setup();
    await user.click(screen.getByTestId("import-agent-btn"));

    expect(screen.getByTestId("import-agent-dialog")).toBeInTheDocument();
    expect(screen.getByTestId("import-drop-zone")).toBeInTheDocument();
  });

  it("import dialog shows strategy options after file selection", async () => {
    renderWithProviders(<AgentsPage />);
    const user = userEvent.setup();
    await user.click(screen.getByTestId("import-agent-btn"));

    // Upload a file
    const fileInput = screen.getByTestId("import-file-input");
    const file = new File(["fake-zip"], "agent.zip", { type: "application/zip" });
    await user.upload(fileInput, file);

    // Strategy options should appear
    await waitFor(() => {
      expect(screen.getByTestId("strategy-create")).toBeInTheDocument();
    });
  });
});
