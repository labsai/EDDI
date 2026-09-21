import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { VersionDiffDialog } from "@/components/editors/version-diff-dialog";

// Mock monaco DiffEditor since it's externalized
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(() => <div data-testid="monaco-editor" />),
  DiffEditor: vi.fn(() => <div data-testid="monaco-diff-editor" />),
}));

describe("VersionDiffDialog", () => {
  const mockFetchVersion = vi.fn((v: number) =>
    Promise.resolve(JSON.stringify({ version: v, data: "test" }))
  );

  const defaultProps = {
    open: true,
    onClose: vi.fn(),
    typeName: "Rules",
    versions: [
      { version: 1, lastModifiedOn: Date.now() - 86400000 },
      { version: 2, lastModifiedOn: Date.now() - 3600000 },
      { version: 3, lastModifiedOn: Date.now() },
    ],
    fetchVersion: mockFetchVersion,
    currentVersion: 3,
  };

  it("renders nothing when open is false", () => {
    const { container } = renderWithProviders(
      <VersionDiffDialog {...defaultProps} open={false} />
    );
    expect(container.innerHTML).toBe("");
  });

  it("renders dialog with title and type name", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    expect(screen.getByText(/Compare Versions/)).toBeInTheDocument();
    expect(screen.getByText(/Rules/)).toBeInTheDocument();
  });

  it("has data-testid version-diff-dialog", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    expect(
      screen.getByTestId("version-diff-dialog")
    ).toBeInTheDocument();
  });

  it("shows left and right version selectors", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    expect(screen.getByTestId("diff-version-left")).toBeInTheDocument();
    expect(screen.getByTestId("diff-version-right")).toBeInTheDocument();
  });

  it("defaults left to previous version, right to current", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    const leftSelect = screen.getByTestId("diff-version-left") as HTMLSelectElement;
    const rightSelect = screen.getByTestId("diff-version-right") as HTMLSelectElement;
    expect(leftSelect.value).toBe("2");
    expect(rightSelect.value).toBe("3");
  });

  it("fetches both versions on mount", async () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    await waitFor(() => {
      expect(mockFetchVersion).toHaveBeenCalledWith(2);
      expect(mockFetchVersion).toHaveBeenCalledWith(3);
    });
  });

  it("renders diff editor when data is loaded", async () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    await waitFor(() => {
      expect(
        screen.getByTestId("monaco-diff-editor")
      ).toBeInTheDocument();
    });
  });

  it("shows footer diff hint", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    expect(
      screen.getByText(/Additions shown in green/)
    ).toBeInTheDocument();
  });

  it("has swap versions button", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    expect(
      screen.getByLabelText("Swap versions")
    ).toBeInTheDocument();
  });

  it("swaps versions when swap button clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    
    const leftSelect = screen.getByTestId("diff-version-left") as HTMLSelectElement;
    const rightSelect = screen.getByTestId("diff-version-right") as HTMLSelectElement;
    expect(leftSelect.value).toBe("2");
    expect(rightSelect.value).toBe("3");

    await user.click(screen.getByLabelText("Swap versions"));
    expect(leftSelect.value).toBe("3");
    expect(rightSelect.value).toBe("2");
  });

  it("has a close button", () => {
    renderWithProviders(<VersionDiffDialog {...defaultProps} />);
    expect(screen.getByLabelText("Close")).toBeInTheDocument();
  });

  it("shows error state on fetch failure", async () => {
    const failFetch = vi.fn(() => Promise.reject(new Error("Fetch failed")));
    renderWithProviders(
      <VersionDiffDialog {...defaultProps} fetchVersion={failFetch} />
    );
    await waitFor(() => {
      expect(screen.getByText("Fetch failed")).toBeInTheDocument();
    });
  });
});
