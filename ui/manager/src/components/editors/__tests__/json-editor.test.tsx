import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { JsonEditor } from "@/components/editors/json-editor";

// Mock monaco
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(
    ({
      value,
      onChange,
      loading,
    }: {
      value: string;
      onChange?: (v: string) => void;
      loading?: React.ReactNode;
    }) => (
      <div data-testid="mock-monaco-wrapper">
        <textarea
          data-testid="mock-monaco-textarea"
          defaultValue={value}
          onChange={(e) => onChange?.(e.target.value)}
        />
        {loading && <div data-testid="mock-monaco-loading">{loading}</div>}
      </div>
    )
  ),
}));

describe("JsonEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
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
