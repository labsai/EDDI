import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConfigEditorLayout } from "@/components/editors/config-editor-layout";

const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: { error: (...args: unknown[]) => toastError(...args), success: vi.fn(), warning: vi.fn() },
}));

// A Monaco stand-in that reports edits, so the JSON tab can be made invalid.
vi.mock("@monaco-editor/react", () => ({
  default: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) => (
    <textarea
      data-testid="mock-monaco"
      value={value}
      onChange={(e) => onChange?.(e.target.value)}
    />
  ),
}));

const props = {
  typeName: "Behavior Rules",
  resourceId: "abc-123",
  data: JSON.stringify({ a: 1 }, null, 2),
  versions: [{ version: 1 }, { version: 2 }],
  currentVersion: 2,
  onVersionChange: vi.fn(),
  onSave: vi.fn(),
};

describe("ConfigEditorLayout — save feedback", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("refuses an invalid-JSON save out loud and moves to the JSON tab", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <ConfigEditorLayout
        {...props}
        onSave={onSave}
        renderFormEditor={(parsed, onChange) => (
          <button
            data-testid="make-change"
            onClick={() => onChange({ ...(parsed as object), b: 2 })}
          >
            change
          </button>
        )}
      />,
    );
    // Dirty through the form, then break the JSON in the JSON tab.
    await user.click(screen.getByTestId("make-change"));
    await user.click(screen.getByTestId("tab-json"));
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: "{ nope" } });
    // Back on the form tab, where the broken JSON used to be invisible.
    await user.click(screen.getByTestId("tab-form"));
    expect(screen.getByTestId("form-view")).toBeInTheDocument();

    await user.click(screen.getByTestId("save-btn"));

    expect(onSave).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("json-view")).toBeInTheDocument();
    expect(screen.getByTestId("json-parse-error")).toBeInTheDocument();
  });

  it("refuses an invalid-JSON Save & Test the same way", async () => {
    const onSaveAndDeploy = vi.fn();
    renderWithProviders(
      <ConfigEditorLayout {...props} onSaveAndDeploy={onSaveAndDeploy} />,
    );
    // No form editor, so the JSON tab is already showing.
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: "{ nope" } });
    await userEvent.setup().click(screen.getByTestId("save-test-btn"));
    expect(onSaveAndDeploy).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("json-parse-error")).toBeInTheDocument();
  });

  it("clears the parse error once the JSON is edited again", async () => {
    renderWithProviders(<ConfigEditorLayout {...props} />);
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: "{ nope" } });
    await userEvent.setup().click(screen.getByTestId("save-btn"));
    expect(screen.getByTestId("json-parse-error")).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: '{"a": 2}' } });
    expect(screen.queryByTestId("json-parse-error")).not.toBeInTheDocument();
  });

  it("hides the stale 'saved' tick as soon as there are unsaved edits again", () => {
    renderWithProviders(
      <ConfigEditorLayout {...props} saveSuccess />,
    );
    expect(screen.getByTestId("save-success")).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: '{"a": 3}' } });
    expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    expect(screen.queryByTestId("save-success")).not.toBeInTheDocument();
  });

  it("explains why the version picker is locked while dirty", () => {
    const { container } = renderWithProviders(<ConfigEditorLayout {...props} />);
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: '{"a": 3}' } });
    expect(screen.getByTestId("version-picker")).toBeDisabled();
    expect(container.querySelector("[title]")).not.toBeNull();
  });

  it("saves on Ctrl+S and on Cmd+S, only when there is something to save", () => {
    const onSave = vi.fn();
    renderWithProviders(<ConfigEditorLayout {...props} onSave={onSave} />);
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });
    expect(onSave).not.toHaveBeenCalled(); // clean

    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: '{"a": 3}' } });
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });
    expect(onSave).toHaveBeenCalledTimes(1);
    expect(onSave).toHaveBeenCalledWith('{"a": 3}');

    fireEvent.keyDown(window, { key: "s", metaKey: true });
    expect(onSave).toHaveBeenCalledTimes(2);
  });

  it("does not save on Ctrl+S in read-only mode", () => {
    const onSave = vi.fn();
    renderWithProviders(<ConfigEditorLayout {...props} onSave={onSave} readOnly />);
    fireEvent.keyDown(window, { key: "s", ctrlKey: true });
    expect(onSave).not.toHaveBeenCalled();
  });
});
