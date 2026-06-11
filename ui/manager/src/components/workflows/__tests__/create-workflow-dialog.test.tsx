import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateWorkflowDialog } from "@/components/workflows/create-workflow-dialog";

describe("CreateWorkflowDialog", () => {
  it("renders nothing when open is false", () => {
    renderWithProviders(
      <CreateWorkflowDialog open={false} onClose={vi.fn()} />
    );
    expect(screen.queryByTestId("create-workflow-dialog")).not.toBeInTheDocument();
  });

  it("renders dialog when open is true", () => {
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    expect(screen.getByTestId("create-workflow-dialog")).toBeInTheDocument();
    expect(screen.getByText("Create New Workflow")).toBeInTheDocument();
  });

  it("shows name and description fields", () => {
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    expect(screen.getByLabelText("Name")).toBeInTheDocument();
    expect(screen.getByLabelText("Description")).toBeInTheDocument();
  });

  it("shows cancel and create buttons", () => {
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.getByText("Create")).toBeInTheDocument();
  });

  it("calls onClose when cancel is clicked", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={onClose} />
    );
    await user.click(screen.getByText("Cancel"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("allows typing in name field", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    const nameInput = screen.getByLabelText("Name");
    await user.type(nameInput, "Test Workflow");
    expect(nameInput).toHaveValue("Test Workflow");
  });

  it("allows typing in description field", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    const descInput = screen.getByLabelText("Description");
    await user.click(descInput);
    await user.type(descInput, "A test description");
    expect(descInput).toHaveValue("A test description");
  });

  it("has placeholder for name", () => {
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    expect(screen.getByPlaceholderText("My Workflow")).toBeInTheDocument();
  });

  it("has placeholder for description", () => {
    renderWithProviders(
      <CreateWorkflowDialog open={true} onClose={vi.fn()} />
    );
    expect(
      screen.getByPlaceholderText("Describe what this workflow does...")
    ).toBeInTheDocument();
  });
});
