import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
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

describe("CreateOrWizardDialog — guided setup is admin-only (OI3, BR6)", () => {
  const renderAsRole = (roles: string[], type: "agent" | "group") =>
    renderWithProviders(
      <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles }}>
        <CreateOrWizardDialog
          open={true}
          onClose={vi.fn()}
          type={type}
          wizardPath="/wizard"
          onQuickCreate={vi.fn()}
        />
      </AuthContext.Provider>,
    );

  it("disables the agent wizard for an editor and keeps Quick Create usable", () => {
    renderAsRole(["eddi-editor"], "agent");
    expect(screen.getByTestId("choice-wizard")).toBeDisabled();
    expect(screen.getByTestId("choice-quick-create")).toBeEnabled();
  });

  it("leaves the agent wizard enabled for an administrator", () => {
    renderAsRole(["eddi-admin"], "agent");
    expect(screen.getByTestId("choice-wizard")).toBeEnabled();
  });

  it("does not gate the group wizard, which has no admin-only endpoint", () => {
    renderAsRole(["eddi-editor"], "group");
    expect(screen.getByTestId("choice-wizard")).toBeEnabled();
  });

  it("offers everything with auth off", () => {
    renderWithProviders(
      <CreateOrWizardDialog open={true} onClose={vi.fn()} type="agent" wizardPath="/wizard" onQuickCreate={vi.fn()} />,
    );
    expect(screen.getByTestId("choice-wizard")).toBeEnabled();
  });
});
