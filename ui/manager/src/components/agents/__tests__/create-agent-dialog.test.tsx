import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateAgentDialog } from "@/components/agents/create-agent-dialog";

describe("CreateAgentDialog", () => {
  const defaultProps = {
    open: true,
    onClose: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders dialog title", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(screen.getByText("Create New Agent")).toBeInTheDocument();
  });

  it("renders name input", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
  });

  it("renders description textarea", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(screen.getByLabelText("Description")).toBeInTheDocument();
  });

  it("renders cancel button", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(screen.getByText("Cancel")).toBeInTheDocument();
  });

  it("renders create button", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(screen.getByText("Create")).toBeInTheDocument();
  });

  it("shows name placeholder", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(
      screen.getByPlaceholderText("My Agent")
    ).toBeInTheDocument();
  });

  it("shows description placeholder", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(
      screen.getByPlaceholderText("Describe what this agent does...")
    ).toBeInTheDocument();
  });

  it("calls onClose when cancel is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    await user.click(screen.getByText("Cancel"));
    expect(defaultProps.onClose).toHaveBeenCalled();
  });

  it("allows typing in name field", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    const nameInput = screen.getByLabelText("Name");
    await user.click(nameInput);
    await user.type(nameInput, "My Test Agent");
    expect(nameInput).toHaveValue("My Test Agent");
  });

  it("allows typing in description field", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    const descInput = screen.getByLabelText("Description");
    await user.click(descInput);
    await user.type(descInput, "A helpful assistant");
    expect(descInput).toHaveValue("A helpful assistant");
  });

  it("has data-testid create-agent-dialog", () => {
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);
    expect(
      screen.getByTestId("create-agent-dialog")
    ).toBeInTheDocument();
  });

  it("submits form and creates agent", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CreateAgentDialog {...defaultProps} />);

    const nameInput = screen.getByLabelText("Name");
    await user.click(nameInput);
    await user.type(nameInput, "New Agent");

    const createBtn = screen.getByText("Create");
    await user.click(createBtn);

    await waitFor(() => {
      expect(defaultProps.onClose).toHaveBeenCalled();
    });
  });
});
