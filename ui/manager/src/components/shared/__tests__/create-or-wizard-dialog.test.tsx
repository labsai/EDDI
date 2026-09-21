import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateOrWizardDialog } from "@/components/shared/create-or-wizard-dialog";

describe("CreateOrWizardDialog", () => {
  it("renders nothing when open is false", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={false}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(screen.queryByTestId("create-or-wizard-dialog")).not.toBeInTheDocument();
  });

  it("renders dialog when open is true", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(screen.getByTestId("create-or-wizard-dialog")).toBeInTheDocument();
  });

  it("shows Quick Create option", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(screen.getByTestId("choice-quick-create")).toBeInTheDocument();
    expect(screen.getByText("Quick Create")).toBeInTheDocument();
  });

  it("shows Guided Setup option", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(screen.getByTestId("choice-wizard")).toBeInTheDocument();
    expect(screen.getByText("Guided Setup")).toBeInTheDocument();
  });

  it("calls onQuickCreate when Quick Create is clicked", async () => {
    const onQuickCreate = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={onQuickCreate}
      />
    );
    await user.click(screen.getByTestId("choice-quick-create"));
    expect(onQuickCreate).toHaveBeenCalledTimes(1);
  });

  it("shows correct type label for agent", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(screen.getByText("New Agent")).toBeInTheDocument();
  });

  it("shows correct type label for group", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="group"
        wizardPath="/wizard/group"
        onQuickCreate={vi.fn()}
      />
    );
    expect(screen.getByText("New Group")).toBeInTheDocument();
  });

  it("shows description text for quick create", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(
      screen.getByText("Set a name and description, configure details later.")
    ).toBeInTheDocument();
  });

  it("shows description text for guided setup", () => {
    renderWithProviders(
      <CreateOrWizardDialog
        open={true}
        onClose={vi.fn()}
        type="agent"
        wizardPath="/wizard/agent"
        onQuickCreate={vi.fn()}
      />
    );
    expect(
      screen.getByText(
        "Step-by-step wizard with LLM provider, prompts, and deployment."
      )
    ).toBeInTheDocument();
  });
});
