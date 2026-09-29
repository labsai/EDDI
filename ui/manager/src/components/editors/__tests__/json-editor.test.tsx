import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { JsonEditor } from "@/components/editors/json-editor";

/**
 * The monaco object the mocked wrapper hands to `beforeMount`. Tests swap this
 * to exercise the real hazard: `languages.json` is a Monaco *language
 * contribution*, and whether it exists depends on which entry point the bundler
 * resolved. A mock that never invoked `beforeMount` at all is why an
 * unconditional `monaco.languages.json.jsonDefaults` deref reached the browser,
 * where it white-screened the page through the top-level error boundary.
 */
let monacoDouble: unknown = null;

vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(
    ({
      value,
      onChange,
      loading,
      beforeMount,
    }: {
      value: string;
      onChange?: (v: string) => void;
      loading?: React.ReactNode;
      beforeMount?: (monaco: unknown) => void;
    }) => {
      // Call it the way the real wrapper does — during render, which is what
      // made the original throw fatal rather than local.
      beforeMount?.(monacoDouble);
      return (
        <div data-testid="mock-monaco-wrapper">
          <textarea
            data-testid="mock-monaco-textarea"
            defaultValue={value}
            onChange={(e) => onChange?.(e.target.value)}
          />
          {loading && <div data-testid="mock-monaco-loading">{loading}</div>}
        </div>
      );
    }
  ),
}));

/** A monaco whose JSON language contribution was not bundled. */
const monacoWithoutJsonLanguage = { languages: {} };

/** A fully-featured monaco, for asserting the happy path still configures schemas. */
function monacoWithJsonLanguage() {
  const setDiagnosticsOptions = vi.fn();
  return {
    monaco: { languages: { json: { jsonDefaults: { setDiagnosticsOptions } } } },
    setDiagnosticsOptions,
  };
}

describe("JsonEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    monacoDouble = monacoWithJsonLanguage().monaco;
  });

  it("renders when Monaco's JSON language contribution is absent, instead of throwing", () => {
    // The real failure mode: `monaco.languages.json` undefined. Dereferencing
    // it during render threw past this component into the app error boundary,
    // blanking the whole page for what should cost at most schema validation.
    monacoDouble = monacoWithoutJsonLanguage;
    expect(() =>
      renderWithProviders(<JsonEditor value='{"key": "value"}' jsonSchema={{ type: "object" }} />),
    ).not.toThrow();
    expect(screen.getByTestId("json-editor")).toBeInTheDocument();
  });

  it("still configures schema diagnostics when the JSON language IS available", () => {
    const { monaco, setDiagnosticsOptions } = monacoWithJsonLanguage();
    monacoDouble = monaco;
    renderWithProviders(<JsonEditor value='{"a":1}' jsonSchema={{ type: "object" }} />);

    expect(setDiagnosticsOptions).toHaveBeenCalledTimes(1);
    const options = setDiagnosticsOptions.mock.calls[0]![0] as { schemas: unknown[] };
    expect(options.schemas).toHaveLength(1);
  });

  it("renders with default data-testid json-editor", () => {
    renderWithProviders(<JsonEditor value='{"key": "value"}' />);
    expect(screen.getByTestId("json-editor")).toBeInTheDocument();
  });

  it("renders with custom testId", () => {
    renderWithProviders(
      <JsonEditor value='{"key": "value"}' testId="custom-editor" />
    );
    expect(screen.getByTestId("custom-editor")).toBeInTheDocument();
  });

  it("displays the value in the mock textarea", () => {
    const json = '{"name": "test"}';
    renderWithProviders(<JsonEditor value={json} />);
    expect(screen.getByTestId("mock-monaco-textarea")).toHaveValue(json);
  });

  it("calls onChange when content changes", async () => {
    renderWithProviders(
      <JsonEditor value='{"a":1}' onChange={onChange} />
    );
    // Simulate a change through the mock textarea
    const textarea = screen.getByTestId("mock-monaco-textarea");
    // fireEvent is fine here since we're interacting with a mock
    textarea.dispatchEvent(
      new Event("change", { bubbles: true })
    );
  });

  it("renders with readOnly prop (no crash)", () => {
    renderWithProviders(
      <JsonEditor value='{"key": "value"}' readOnly />
    );
    expect(screen.getByTestId("json-editor")).toBeInTheDocument();
  });
});

describe("JsonEditor schema scope", () => {
  type Opts = { schemas: { fileMatch: string[]; schema: unknown }[] };

  it("scopes its schema to its own model instead of every JSON model on the page", async () => {
    // fileMatch: ["*"] validated unrelated editors (another resource's JSON
    // tab, the diff editor) against this schema.
    const { monaco, setDiagnosticsOptions } = monacoWithJsonLanguage();
    monacoDouble = monaco;
    const { default: Editor } = await import("@monaco-editor/react");
    renderWithProviders(<JsonEditor value="{}" jsonSchema={{ title: "llm" }} />);

    const opts = setDiagnosticsOptions.mock.lastCall![0] as Opts;
    expect(opts.schemas).toHaveLength(1);
    expect(opts.schemas[0]!.fileMatch).not.toContain("*");
    // The same path is the one handed to Monaco as the model path.
    const path = (vi.mocked(Editor).mock.lastCall![0] as { path?: string }).path;
    expect(path).toBeTruthy();
    expect(opts.schemas[0]!.fileMatch).toEqual([path]);
  });

  it("keeps two editors' schemas apart and drops one when its editor unmounts", () => {
    const { monaco, setDiagnosticsOptions } = monacoWithJsonLanguage();
    monacoDouble = monaco;
    const first = renderWithProviders(<JsonEditor value="{}" jsonSchema={{ title: "a" }} />);
    renderWithProviders(<JsonEditor value="{}" jsonSchema={{ title: "b" }} testId="second" />);

    // The second mount used to replace the first editor's schema outright.
    let opts = setDiagnosticsOptions.mock.lastCall![0] as Opts;
    expect(opts.schemas.map((s) => s.schema)).toEqual([{ title: "a" }, { title: "b" }]);
    expect(new Set(opts.schemas.map((s) => s.fileMatch[0])).size).toBe(2);

    first.unmount();
    opts = setDiagnosticsOptions.mock.lastCall![0] as Opts;
    expect(opts.schemas.map((s) => s.schema)).toEqual([{ title: "b" }]);
  });

  it("contributes no schema at all when it has none", () => {
    const { monaco, setDiagnosticsOptions } = monacoWithJsonLanguage();
    monacoDouble = monaco;
    renderWithProviders(<JsonEditor value="{}" />);
    const calls = setDiagnosticsOptions.mock.calls as [Opts][];
    for (const [opts] of calls) expect(opts.schemas).toEqual([]);
  });
});
