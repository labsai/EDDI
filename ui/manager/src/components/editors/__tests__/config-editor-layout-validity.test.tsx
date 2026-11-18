import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { useReportInvalid } from "@eddi/app-kit/components/editors/editor-validity";
import { ConfigEditorLayout } from "@/components/editors/config-editor-layout";
import { validateLlmDocument } from "@/components/editors/document-validators";

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
}));
vi.mock("@monaco-editor/react", () => ({
  default: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) => (
    <textarea data-testid="mock-monaco" value={value} onChange={(e) => onChange?.(e.target.value)} />
  ),
}));

const base = {
  typeName: "LLM",
  resourceId: "abc",
  data: JSON.stringify({ temperature: "0.7" }, null, 2),
  versions: [{ version: 1 }],
  currentVersion: 1,
  onVersionChange: vi.fn(),
};

/** A form editor with one field that reports itself invalid when it is not a number. */
function Probe({ parsed, onChange }: { parsed: { temperature?: string }; onChange: (v: unknown) => void }) {
  const value = parsed.temperature ?? "";
  useReportInvalid(Number.isFinite(Number(value)) ? null : "Temperature must be a number.");
  return (
    <>
      <input
        data-testid="temp"
        value={value}
        onChange={(e) => onChange({ ...parsed, temperature: e.target.value })}
      />
      <output data-testid="draft">{JSON.stringify(parsed)}</output>
    </>
  );
}

const form = (parsed: unknown, onChange: (v: unknown) => void) => (
  <Probe parsed={parsed as { temperature?: string }} onChange={onChange} />
);

describe("ConfigEditorLayout — field validity and failed saves", () => {
  it("disables Save with the reason while a field reports a problem, and never saves", async () => {
    const onSave = vi.fn();
    renderWithProviders(<ConfigEditorLayout {...base} onSave={onSave} renderFormEditor={form} />);
    fireEvent.change(screen.getByTestId("temp"), { target: { value: "abc" } });
    expect(screen.getByTestId("save-btn")).toBeDisabled();
    expect(screen.getByTestId("save-btn")).toHaveAttribute("title", "Temperature must be a number.");
    expect(screen.getByTestId("save-blocked-reason")).toHaveTextContent("Temperature must be a number.");
    fireEvent.keyDown(screen.getByTestId("config-editor-layout"), { key: "s", ctrlKey: true });
    expect(onSave).not.toHaveBeenCalled();

    // Fixing the field releases Save.
    fireEvent.change(screen.getByTestId("temp"), { target: { value: "0.5" } });
    expect(screen.getByTestId("save-btn")).toBeEnabled();
    expect(screen.queryByTestId("save-blocked-reason")).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByTestId("save-btn"));
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it("keeps the draft after a failed save, even when the source data changes afterwards", async () => {
    const onSave = vi.fn();
    const { rerender } = renderWithProviders(
      <ConfigEditorLayout {...base} onSave={onSave} renderFormEditor={form} />,
    );
    fireEvent.change(screen.getByTestId("temp"), { target: { value: "0.2" } });
    await userEvent.setup().click(screen.getByTestId("save-btn"));
    expect(onSave).toHaveBeenCalledTimes(1);

    // The save failed: the layout is told so.
    rerender(
      <ConfigEditorLayout {...base} onSave={onSave} renderFormEditor={form} saveError="Not saved: conflict" />,
    );
    expect(screen.getByTestId("save-error")).toHaveTextContent("Not saved: conflict");

    // A later refetch brings different source data (another tab saved).
    rerender(
      <ConfigEditorLayout
        {...base}
        data={JSON.stringify({ temperature: "0.9" }, null, 2)}
        onSave={onSave}
        renderFormEditor={form}
        saveError="Not saved: conflict"
      />,
    );
    expect(screen.getByTestId("draft")).toHaveTextContent('{"temperature":"0.2"}');
    expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
  });

  it("shows the warnings a successful save answered with", () => {
    renderWithProviders(
      <ConfigEditorLayout {...base} onSave={vi.fn()} renderFormEditor={form} saveWarnings={["Vectors are stale"]} />,
    );
    expect(screen.getByTestId("save-warnings")).toHaveTextContent("Vectors are stale");
  });

  it("keeps the draft when a retry fails with the very same error text, then a refetch lands", async () => {
    const onSave = vi.fn();
    const user = userEvent.setup();
    const ui = (extra: Record<string, unknown>, data = base.data) => (
      <ConfigEditorLayout {...base} data={data} onSave={onSave} renderFormEditor={form} {...extra} />
    );
    const { rerender } = renderWithProviders(ui({}));
    fireEvent.change(screen.getByTestId("temp"), { target: { value: "0.2" } });
    for (let attempt = 0; attempt < 2; attempt++) {
      await user.click(screen.getByTestId("save-btn"));
      rerender(ui({ isSaving: true }));
      rerender(ui({ isSaving: false, saveError: "Not saved: conflict" }));
    }
    rerender(ui({ saveError: "Not saved: conflict" }, JSON.stringify({ temperature: "0.9" }, null, 2)));
    expect(screen.getByTestId("draft")).toHaveTextContent('{"temperature":"0.2"}');
  });

  it("blocks an invalid value typed on the JSON tab, where no form editor reports it", () => {
    const onSave = vi.fn();
    const doc = (temperature: string, patterns = "ok") =>
      JSON.stringify({
        tasks: [{ parameters: { temperature }, inputGuardrails: [{ type: "regex-filter", parameters: { patterns } }] }],
      });
    renderWithProviders(
      <ConfigEditorLayout {...base} data={doc("0.7")} onSave={onSave} validateDocument={validateLlmDocument} />,
    );
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: doc("hot") } });
    expect(screen.getByTestId("save-btn")).toBeDisabled();
    expect(screen.getByTestId("save-blocked-reason")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: doc("0.7", "(unclosed") } });
    expect(screen.getByTestId("save-btn")).toBeDisabled();

    // A range overrun is not blocked: stored configs and gateways with wider ranges keep saving.
    fireEvent.change(screen.getByTestId("mock-monaco"), { target: { value: doc("3.5") } });
    expect(screen.getByTestId("save-btn")).toBeEnabled();
  });
});
