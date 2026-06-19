import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ContentEditor } from "@/components/editors/content-editor";

// Mock monaco
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(
    ({
      value,
      onChange,
    }: {
      value: string;
      onChange?: (v: string) => void;
    }) => (
      <textarea
        data-testid="mock-monaco-textarea"
        defaultValue={value}
        onChange={(e) => onChange?.(e.target.value)}
      />
    )
  ),
}));

describe("ContentEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with default data-testid content-editor", () => {
    renderWithProviders(
      <ContentEditor value="Hello" onChange={onChange} />
    );
    expect(screen.getByTestId("content-editor")).toBeInTheDocument();
  });

  it("renders with custom testId", () => {
    renderWithProviders(
      <ContentEditor value="Hello" onChange={onChange} testId="my-editor" />
    );
    expect(screen.getByTestId("my-editor")).toBeInTheDocument();
  });

  it("shows expand button", () => {
    renderWithProviders(
      <ContentEditor value="Hello" onChange={onChange} />
    );
    expect(
      screen.getByTestId("content-editor-expand-btn")
    ).toBeInTheDocument();
  });

  it("shows placeholder when value is empty", () => {
    renderWithProviders(
      <ContentEditor
        value=""
        onChange={onChange}
        placeholder="Enter text..."
      />
    );
    expect(screen.getByText("Enter text...")).toBeInTheDocument();
  });

  it("does not show placeholder when value is set", () => {
    renderWithProviders(
      <ContentEditor
        value="Some content"
        onChange={onChange}
        placeholder="Enter text..."
      />
    );
    expect(screen.queryByText("Enter text...")).not.toBeInTheDocument();
  });

  it("renders status bar with line and char count", () => {
    renderWithProviders(
      <ContentEditor value="Hello" onChange={onChange} />
    );
    // Status bar shows "1 lines" and "5 chars" split across child elements
    expect(screen.getByText(/1\s+lines/)).toBeInTheDocument();
    expect(screen.getByText(/5\s+chars/)).toBeInTheDocument();
  });
});
