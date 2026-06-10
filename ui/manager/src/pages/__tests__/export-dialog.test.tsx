import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ExportAgentDialog } from "@/components/agents/export-agent-dialog";

function renderDialog(props: Partial<React.ComponentProps<typeof ExportAgentDialog>> = {}) {
  return renderWithProviders(
    <ExportAgentDialog
      open
      onClose={() => {}}
      agentId="agent1"
      agentVersion={3}
      {...props}
    />
  );
}

describe("ExportAgentDialog", () => {
  it("renders loading state then resource tree", async () => {
    renderDialog();
    expect(screen.getByTestId("export-agent-dialog")).toBeInTheDocument();

    // Wait for the preview data to load
    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });
  });

  it("shows resource types with badges", async () => {
    renderDialog();
    await waitFor(() => {
      expect(screen.getByText("agent")).toBeInTheDocument();
      expect(screen.getByText("workflow")).toBeInTheDocument();
      expect(screen.getByText("behavior")).toBeInTheDocument();
    });
  });

  it("required items have checkboxes that are disabled", async () => {
    renderDialog();
    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });

    const checkboxes = screen.getAllByRole("checkbox");
    // Find disabled checkboxes (required items)
    const disabledCheckboxes = checkboxes.filter((cb) => cb.hasAttribute("disabled"));
    expect(disabledCheckboxes.length).toBeGreaterThan(0);
  });

  it("non-required items can be toggled", async () => {
    renderDialog();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Support Rules")).toBeInTheDocument();
    });

    // Find a non-required checkbox and toggle it
    const checkboxes = screen.getAllByRole("checkbox");
    // Filter for enabled checkboxes
    const enabledCheckboxes = checkboxes.filter((cb) => !cb.hasAttribute("disabled"));
    expect(enabledCheckboxes.length).toBeGreaterThan(0);

    const firstEnabled = enabledCheckboxes[0]!;
    const wasChecked = (firstEnabled as HTMLInputElement).checked;
    await user.click(firstEnabled);
    expect((firstEnabled as HTMLInputElement).checked).toBe(!wasChecked);
  });

  it("export button is present with correct testid", async () => {
    renderDialog();
    await waitFor(() => {
      expect(screen.getByTestId("export-confirm-btn")).toBeInTheDocument();
    });
  });

  it("toggle all checkbox selects and deselects resources", async () => {
    renderDialog();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("export-confirm-btn")).toBeInTheDocument();
    });

    const selectAllCheckbox = screen.getByTestId("select-all-checkbox") as HTMLInputElement;

    // Initially all should be selected
    expect(selectAllCheckbox.checked).toBe(true);

    // Toggle off — should deselect non-required items
    await user.click(selectAllCheckbox);

    // The select-all checkbox should now be unchecked (only required remain)
    const enabledCheckboxes = screen.getAllByRole("checkbox").filter(
      (cb) => !cb.hasAttribute("disabled") && cb !== selectAllCheckbox
    );
    const allUnchecked = enabledCheckboxes.every(
      (cb) => !(cb as HTMLInputElement).checked
    );
    expect(allUnchecked).toBe(true);

    // Toggle on — should re-select all
    await user.click(selectAllCheckbox);
    expect(selectAllCheckbox.checked).toBe(true);
    const allCheckedAfter = screen.getAllByRole("checkbox").every(
      (cb) => (cb as HTMLInputElement).checked
    );
    expect(allCheckedAfter).toBe(true);
  });

  it("does not render when open is false", () => {
    renderWithProviders(
      <ExportAgentDialog
        open={false}
        onClose={() => {}}
        agentId="agent1"
        agentVersion={3}
      />
    );
    expect(screen.queryByTestId("export-agent-dialog")).not.toBeInTheDocument();
  });
});
