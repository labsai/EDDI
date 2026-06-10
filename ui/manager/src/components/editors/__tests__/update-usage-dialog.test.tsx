import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { UpdateUsageDialog } from "@/components/editors/update-usage-dialog";
import type { ResourceUsage } from "@/lib/api/resource-usage";

const mockUsages: ResourceUsage[] = [
  {
    agentId: "agent1",
    agentName: "Support Agent",
    agentVersion: 3,
    workflowId: "wf1",
    workflowName: "Support Workflow",
    workflowVersion: 2,
  },
  {
    agentId: "agent2",
    agentName: "Sales Agent",
    agentVersion: 1,
    workflowId: "wf2",
    workflowName: "Sales Workflow",
    workflowVersion: 1,
  },
];

describe("UpdateUsageDialog", () => {
  const defaultProps = {
    usages: mockUsages,
    isUpdating: false,
    onConfirm: vi.fn(),
    onDismiss: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders nothing when usages is empty", () => {
    const { container } = renderWithProviders(
      <UpdateUsageDialog {...defaultProps} usages={[]} />
    );
    expect(container.innerHTML).toBe("");
  });

  it("renders dialog with usage count", () => {
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    expect(screen.getByTestId("update-usage-dialog")).toBeInTheDocument();
  });

  it("shows agent and workflow names", () => {
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    expect(screen.getByText(/Support Agent/)).toBeInTheDocument();
    expect(screen.getByText(/Support Workflow/)).toBeInTheDocument();
    expect(screen.getByText(/Sales Agent/)).toBeInTheDocument();
    expect(screen.getByText(/Sales Workflow/)).toBeInTheDocument();
  });

  it("all items are selected by default", () => {
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    const checkbox0 = screen.getByTestId("usage-checkbox-0") as HTMLInputElement;
    const checkbox1 = screen.getByTestId("usage-checkbox-1") as HTMLInputElement;
    expect(checkbox0.checked).toBe(true);
    expect(checkbox1.checked).toBe(true);
  });

  it("shows confirm and dismiss buttons", () => {
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    expect(screen.getByTestId("confirm-cascade-btn")).toBeInTheDocument();
    expect(screen.getByTestId("dismiss-cascade-btn")).toBeInTheDocument();
    expect(screen.getByText("Update Selected")).toBeInTheDocument();
    expect(screen.getByText("Skip")).toBeInTheDocument();
  });

  it("calls onConfirm with selected usages", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    await user.click(screen.getByTestId("confirm-cascade-btn"));
    expect(defaultProps.onConfirm).toHaveBeenCalledWith(mockUsages);
  });

  it("calls onDismiss when skip is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    await user.click(screen.getByTestId("dismiss-cascade-btn"));
    expect(defaultProps.onDismiss).toHaveBeenCalled();
  });

  it("can toggle a usage checkbox", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    const checkbox0 = screen.getByTestId("usage-checkbox-0") as HTMLInputElement;
    expect(checkbox0.checked).toBe(true);
    await user.click(checkbox0);
    expect(checkbox0.checked).toBe(false);
  });

  it("calls onConfirm with only selected usages after toggling", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    // Deselect first usage
    await user.click(screen.getByTestId("usage-checkbox-0"));
    await user.click(screen.getByTestId("confirm-cascade-btn"));
    expect(defaultProps.onConfirm).toHaveBeenCalledWith([mockUsages[1]]);
  });

  it("disables confirm button when no items selected", async () => {
    const user = userEvent.setup();
    renderWithProviders(<UpdateUsageDialog {...defaultProps} />);
    await user.click(screen.getByTestId("usage-checkbox-0"));
    await user.click(screen.getByTestId("usage-checkbox-1"));
    expect(screen.getByTestId("confirm-cascade-btn")).toBeDisabled();
  });

  it("shows updating state", () => {
    renderWithProviders(
      <UpdateUsageDialog {...defaultProps} isUpdating={true} />
    );
    expect(screen.getByText("Updating...")).toBeInTheDocument();
  });

  it("disables buttons when updating", () => {
    renderWithProviders(
      <UpdateUsageDialog {...defaultProps} isUpdating={true} />
    );
    expect(screen.getByTestId("dismiss-cascade-btn")).toBeDisabled();
  });
});
