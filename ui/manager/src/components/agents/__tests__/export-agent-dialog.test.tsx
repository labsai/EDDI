import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ExportAgentDialog } from "@/components/agents/export-agent-dialog";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("ExportAgentDialog", () => {
  const defaultProps = {
    open: true,
    onClose: vi.fn(),
    agentId: "agent1",
    agentVersion: 3,
  };

  it("renders nothing when open is false", () => {
    const { container } = renderWithProviders(
      <ExportAgentDialog {...defaultProps} open={false} />
    );
    expect(container.innerHTML).toBe("");
  });

  it("renders dialog with title when open", () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    expect(screen.getByText("Export Agent")).toBeInTheDocument();
  });

  it("shows loading state initially", () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    expect(
      screen.getByText("Loading resource tree...")
    ).toBeInTheDocument();
  });

  it("shows resource tree when loaded", async () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByText("Select resources to export")).toBeInTheDocument();
    });
    // Agent root
    expect(screen.getByText("Support Agent")).toBeInTheDocument();
    // Workflow
    expect(screen.getByText("Support Ticket Pipeline")).toBeInTheDocument();
  });

  it("shows export button", async () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByTestId("export-confirm-btn")).toBeInTheDocument();
    });
    expect(screen.getByText("Export Selected")).toBeInTheDocument();
  });

  it("shows all resources selected by default", async () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(
        screen.getByText("All resources selected")
      ).toBeInTheDocument();
    });
  });

  it("shows select all checkbox", async () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByText("All resources selected")).toBeInTheDocument();
    });
    const checkboxes = screen.getAllByRole("checkbox");
    // At least the select all checkbox plus resource checkboxes
    expect(checkboxes.length).toBeGreaterThan(1);
  });

  it("has data-testid export-agent-dialog", async () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(
        screen.getByTestId("export-agent-dialog")
      ).toBeInTheDocument();
    });
  });

  it("shows error state when API fails", async () => {
    server.use(
      http.post("*/backup/export/:agentId/preview", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByText(/error/i)).toBeInTheDocument();
    });
  });

  it("shows snippets group", async () => {
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByText("Snippets")).toBeInTheDocument();
    });
    expect(screen.getByText("System Prompt Base")).toBeInTheDocument();
  });

  it("can toggle a non-required resource", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ExportAgentDialog {...defaultProps} />);
    await waitFor(() => {
      expect(screen.getByText("Support Rules")).toBeInTheDocument();
    });

    // Find the checkbox for Support Rules
    const label = screen.getByText("Support Rules");
    const checkbox = label.closest("label")!.querySelector("input[type='checkbox']") as HTMLInputElement;
    expect(checkbox.checked).toBe(true);

    await user.click(checkbox);
    expect(checkbox.checked).toBe(false);
  });
});
