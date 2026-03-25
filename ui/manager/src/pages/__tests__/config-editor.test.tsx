import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";
import { VersionPicker } from "@/components/editors/version-picker";
import { ConfigEditorLayout } from "@/components/editors/config-editor-layout";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { render } from "@testing-library/react";

// Monaco doesn't render in JSDOM, so we mock it
vi.mock("@monaco-editor/react", () => ({
  default: ({
    value,
    onChange,
    "data-testid": testId,
  }: {
    value: string;
    onChange?: (val: string) => void;
    "data-testid"?: string;
  }) => (
    <textarea
      data-testid={testId ?? "monaco-mock"}
      value={value}
      onChange={(e) => onChange?.(e.target.value)}
      aria-label="JSON editor"
    />
  ),
}));

function renderWithRoute(path: string, element: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/resources/:type/:id" element={element} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("VersionPicker", () => {
  it("renders as badge when only one version", () => {
    renderWithProviders(
      <VersionPicker
        versions={[{ version: 1 }]}
        current={1}
        onChange={() => {}}
      />
    );
    expect(screen.getByTestId("version-badge")).toBeInTheDocument();
    expect(screen.getByText("v1")).toBeInTheDocument();
  });

  it("renders as select when multiple versions", () => {
    renderWithProviders(
      <VersionPicker
        versions={[
          { version: 2, lastModifiedOn: Date.now() - 3600000 },
          { version: 1, lastModifiedOn: Date.now() - 86400000 },
        ]}
        current={2}
        onChange={() => {}}
      />
    );
    expect(screen.getByTestId("version-picker")).toBeInTheDocument();
  });

  it("calls onChange when version selected", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <VersionPicker
        versions={[
          { version: 2, lastModifiedOn: Date.now() },
          { version: 1, lastModifiedOn: Date.now() - 86400000 },
        ]}
        current={2}
        onChange={onChange}
      />
    );
    const select = screen.getByTestId("version-picker");
    await user.selectOptions(select, "1");
    expect(onChange).toHaveBeenCalledWith(1);
  });
});

describe("ConfigEditorLayout", () => {
  const defaultProps = {
    typeName: "Behavior Rules",
    resourceId: "test-id-123",
    data: JSON.stringify({ type: "behavior", config: {} }, null, 2),
    versions: [{ version: 1 }],
    currentVersion: 1,
    onVersionChange: vi.fn(),
    onSave: vi.fn(),
  };

  it("renders editor layout container", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
  });

  it("renders Form and JSON tabs", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("tab-form")).toBeInTheDocument();
    expect(screen.getByTestId("tab-json")).toBeInTheDocument();
  });

  it("defaults to JSON tab", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("json-view")).toBeInTheDocument();
  });

  it("switches to Form tab on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    await user.click(screen.getByTestId("tab-form"));
    expect(screen.getByTestId("form-view")).toBeInTheDocument();
  });

  it("shows form placeholder when no children provided", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    await user.click(screen.getByTestId("tab-form"));
    expect(screen.getByText(/Visual editor coming soon/)).toBeInTheDocument();
  });

  it("save button is disabled when not dirty", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("save-btn")).toBeDisabled();
  });

  it("discard button is disabled when not dirty", () => {
    renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
    expect(screen.getByTestId("discard-btn")).toBeDisabled();
  });

  it("shows success message when saveSuccess is true", () => {
    renderWithProviders(
      <ConfigEditorLayout {...defaultProps} saveSuccess={true} />
    );
    expect(screen.getByTestId("save-success")).toBeInTheDocument();
  });

  it("shows error message when saveError is set", () => {
    renderWithProviders(
      <ConfigEditorLayout
        {...defaultProps}
        saveError="Failed to save"
      />
    );
    expect(screen.getByTestId("save-error")).toBeInTheDocument();
  });
});

describe("ResourceDetailPage with editor", () => {
  it("renders editor layout when data loads", async () => {
    renderWithRoute(
      "/manage/resources/rules/res1",
      <ResourceDetailPage />
    );
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });
  });

  it("renders Form and JSON tabs", async () => {
    renderWithRoute(
      "/manage/resources/rules/res1",
      <ResourceDetailPage />
    );
    await waitFor(() => {
      expect(screen.getByTestId("tab-form")).toBeInTheDocument();
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });
  });

  it("renders back link", () => {
    renderWithRoute(
      "/manage/resources/rules/res1",
      <ResourceDetailPage />
    );
    expect(screen.getByTestId("back-to-list")).toBeInTheDocument();
  });

  it("renders delete and duplicate buttons", () => {
    renderWithRoute(
      "/manage/resources/rules/res1",
      <ResourceDetailPage />
    );
    expect(screen.getByText("Delete")).toBeInTheDocument();
    expect(screen.getByText("Duplicate")).toBeInTheDocument();
  });
});
