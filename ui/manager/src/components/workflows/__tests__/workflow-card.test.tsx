import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { WorkflowCard } from "@/components/workflows/workflow-card";

const baseWorkflow = {
  id: "wf-123",
  version: 3,
  name: "Customer Support Flow",
  description: "Handles customer tickets",
  lastModifiedOn: Date.now() - 7200000, // 2 hours ago
  resource: "eddi://ai.labs.package/packagestore/packages/wf-123?version=3",
  createdOn: Date.now() - 86400000,
};

describe("WorkflowCard", () => {
  it("renders the workflow name", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Customer Support Flow")).toBeInTheDocument();
  });

  it("renders the workflow description", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Handles customer tickets")).toBeInTheDocument();
  });

  it("renders the workflow ID", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("wf-123")).toBeInTheDocument();
  });

  it("renders version badge", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("v3")).toBeInTheDocument();
  });

  it("has correct data-testid", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByTestId("workflow-card-wf-123")).toBeInTheDocument();
  });

  it("shows 'Unnamed Workflow' for empty name", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={{ ...baseWorkflow, name: "" }}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("Unnamed Workflow")).toBeInTheDocument();
  });

  it("shows 'No description' for empty description", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={{ ...baseWorkflow, description: "" }}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(screen.getByText("No description")).toBeInTheDocument();
  });

  it("links to the workflow view page", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    const link = screen.getByText("Customer Support Flow").closest("a");
    expect(link).toHaveAttribute("href", "/manage/workflowview/wf-123");
  });

  it("shows context menu on menu button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    await user.click(screen.getByTestId("workflow-menu-wf-123"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    expect(screen.getByText("Delete")).toBeInTheDocument();
  });

  it("calls onDuplicate when duplicate is clicked", async () => {
    const onDuplicate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={onDuplicate}
        onDelete={vi.fn()}
      />
    );
    await user.click(screen.getByTestId("workflow-menu-wf-123"));
    await user.click(screen.getByText("Duplicate"));
    expect(onDuplicate).toHaveBeenCalledWith("wf-123", 3);
  });

  it("calls onDelete when delete is clicked", async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={onDelete}
      />
    );
    await user.click(screen.getByTestId("workflow-menu-wf-123"));
    await user.click(screen.getByText("Delete"));
    expect(onDelete).toHaveBeenCalledWith("wf-123", 3);
  });

  it("closes context menu after action", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    await user.click(screen.getByTestId("workflow-menu-wf-123"));
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
    await user.click(screen.getByText("Duplicate"));
    expect(screen.queryByText("Duplicate")).not.toBeInTheDocument();
  });

  it("shows relative time ago", () => {
    renderWithProviders(
      <WorkflowCard
        workflow={baseWorkflow}
        onDuplicate={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    // 2 hours ago should show "2h ago"
    expect(screen.getByText("2h ago")).toBeInTheDocument();
  });
});
