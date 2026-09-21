import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConfigEditorLayout } from "@/components/editors/config-editor-layout";
import type { VersionInfo } from "@/components/editors/version-picker";

// Mock monaco
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(({ value }: { value: string }) => (
    <textarea data-testid="mock-monaco" defaultValue={value} />
  )),
}));

const defaultVersions: VersionInfo[] = [
  { version: 1 },
  { version: 2 },
];

const defaultProps = {
  typeName: "Behavior Rules",
  resourceId: "abc-123",
  data: JSON.stringify({ extensions: [] }, null, 2),
  versions: defaultVersions,
  currentVersion: 1,
  onVersionChange: vi.fn(),
  onSave: vi.fn(),
};

describe("ConfigEditorLayout", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid config-editor-layout", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
  });

  it("shows type name in header", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByText("Behavior Rules")).toBeInTheDocument();
  });

  it("shows resource ID in header", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByText("abc-123")).toBeInTheDocument();
  });

  it("shows Form and JSON tabs", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("tab-form")).toBeInTheDocument();
    expect(screen.getByTestId("tab-json")).toBeInTheDocument();
  });

  it("defaults to json tab when no children and no renderFormEditor", () => {
    // hasFormEditor is false → defaults to JSON tab
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("json-view")).toBeInTheDocument();
  });

  it("defaults to form tab when children are provided", () => {
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps}>
        <div data-testid="child-editor">Custom Editor</div>
      </ConfigEditorLayout>
    );
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
    expect(screen.getByTestId("child-editor")).toBeInTheDocument();
  });

  it("defaults to form tab when renderFormEditor is provided", () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        renderFormEditor={() => <div data-testid="custom-form">Form</div>}
      />
    );
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
    expect(screen.getByTestId("custom-form")).toBeInTheDocument();
  });

  it("shows form placeholder when children provided but no renderFormEditor, switching to form tab", async () => {
    const user = userEvent.setup();
    // Without children, default is JSON tab
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    // Switch to form tab
    await user.click(screen.getByTestId("tab-form"));
    expect(
      screen.getByText(/Visual editor coming soon/)
    ).toBeInTheDocument();
  });

  it("switches to JSON tab on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps}>
        <div>Form Content</div>
      </ConfigEditorLayout>
    );
    // Default is form tab with children
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
    await user.click(screen.getByTestId("tab-json"));
    expect(screen.getByTestId("json-view")).toBeInTheDocument();
  });

  it("shows save and discard buttons when not readOnly", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("save-btn")).toBeInTheDocument();
    expect(screen.getByTestId("discard-btn")).toBeInTheDocument();
  });

  it("hides save and discard buttons when readOnly", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} readOnly />);
    expect(screen.queryByTestId("save-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("discard-btn")).not.toBeInTheDocument();
  });

  it("shows save success message", () => {
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps} saveSuccess />
    );
    expect(screen.getByTestId("save-success")).toBeInTheDocument();
  });

  it("shows save error message", () => {
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps} saveError="Failed to save" />
    );
    expect(screen.getByTestId("save-error")).toBeInTheDocument();
    expect(screen.getByText("Failed to save")).toBeInTheDocument();
  });

  it("shows compare button when onCompare provided and multiple versions", () => {
    const onCompare = vi.fn();
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps} onCompare={onCompare} />
    );
    expect(screen.getByTestId("compare-versions-btn")).toBeInTheDocument();
  });

  it("hides compare button when only one version", () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        versions={[{ version: 1 }]}
        onCompare={vi.fn()}
      />
    );
    expect(
      screen.queryByTestId("compare-versions-btn")
    ).not.toBeInTheDocument();
  });

  it("shows Save & Test button when onSaveAndDeploy provided", () => {
    const onSaveAndDeploy = vi.fn();
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        onSaveAndDeploy={onSaveAndDeploy}
      />
    );
    expect(screen.getByTestId("save-test-btn")).toBeInTheDocument();
  });

  it("shows error message when editedData is invalid JSON with renderFormEditor", () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        data="not valid json"
        renderFormEditor={() => <div>Form</div>}
      />
    );
    expect(screen.getByText(/Invalid JSON/)).toBeInTheDocument();
  });

  // --- Dirty state detection ---

  it("shows dirty indicator when form data changes", async () => {
    const onSave = vi.fn();
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        onSave={onSave}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="trigger-change"
            onClick={() => onChange({ ...(parsed as object), modified: true })}
          >
            Modify
          </button>
        )}
      />
    );
    expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("trigger-change"));
    expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
  });

  it("enables save button when dirty", async () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="trigger-change"
            onClick={() => onChange({ ...(parsed as object), modified: true })}
          >
            Modify
          </button>
        )}
      />
    );
    expect(screen.getByTestId("save-btn")).toBeDisabled();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("trigger-change"));
    expect(screen.getByTestId("save-btn")).not.toBeDisabled();
  });

  it("calls onSave with edited data when save clicked", async () => {
    const onSave = vi.fn();
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        onSave={onSave}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="trigger-change"
            onClick={() => onChange({ ...(parsed as object), modified: true })}
          >
            Modify
          </button>
        )}
      />
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId("trigger-change"));
    await user.click(screen.getByTestId("save-btn"));
    expect(onSave).toHaveBeenCalledWith(
      expect.stringContaining("\"modified\": true")
    );
  });

  it("shows discard confirmation dialog when discard clicked", async () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="trigger-change"
            onClick={() => onChange({ ...(parsed as object), modified: true })}
          >
            Modify
          </button>
        )}
      />
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId("trigger-change"));
    await user.click(screen.getByTestId("discard-btn"));
    expect(screen.getByText(/Discard Changes/)).toBeInTheDocument();
  });

  it("discards changes when confirmed in dialog", async () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="trigger-change"
            onClick={() => onChange({ ...(parsed as object), modified: true })}
          >
            Modify
          </button>
        )}
      />
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId("trigger-change"));
    expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    await user.click(screen.getByTestId("discard-btn"));
    // Click confirm in the dialog
    await user.click(screen.getByTestId("unsaved-confirm"));
    // Dirty indicator should be gone
    expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();
  });

  it("calls onSaveAndDeploy when Save & Test clicked and dirty", async () => {
    const onSaveAndDeploy = vi.fn();
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        onSaveAndDeploy={onSaveAndDeploy}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="trigger-change"
            onClick={() => onChange({ ...(parsed as object), deployed: true })}
          >
            Modify
          </button>
        )}
      />
    );
    const user = userEvent.setup();
    await user.click(screen.getByTestId("trigger-change"));
    await user.click(screen.getByTestId("save-test-btn"));
    expect(onSaveAndDeploy).toHaveBeenCalledWith(
      expect.stringContaining("\"deployed\": true")
    );
  });

  // --- Keyboard navigation ---

  it("switches tabs with arrow keys", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps}>
        <div>Form Content</div>
      </ConfigEditorLayout>
    );
    // Start on form tab
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
    // Focus the active tab and press ArrowRight
    const formTab = screen.getByTestId("tab-form");
    formTab.focus();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByTestId("json-view")).toBeInTheDocument();
    // Press ArrowLeft to go back to form
    await user.keyboard("{ArrowLeft}");
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
  });

  it("shows Saving... text when isSaving is true", () => {
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps} isSaving />
    );
    expect(screen.getByText("Saving...")).toBeInTheDocument();
  });

  it("shows Deploying… text when isSaveAndDeploying is true", () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        onSaveAndDeploy={vi.fn()}
        isSaveAndDeploying
      />
    );
    expect(screen.getByText("Deploying…")).toBeInTheDocument();
  });
});
