import { describe, expect, it, vi } from "vitest";
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

  /**
   * The dirty lifecycle — the half of this component nothing asserted.
   *
   * The two tests above pin the *clean* state (save disabled, discard
   * disabled), which is exactly the state an editor that never registers an
   * edit is permanently in. Mutation-checked against the audit's surviving
   * mutants: with `const isDirty = false` in `config-editor-layout.tsx`, all
   * 36 tests in this file and `resource-detail-rules` passed. Each case below
   * fails against that mutant, and against the narrower one that removes only
   * the dirty badge.
   */
  describe("once an edit is made", () => {
    const EDITED = '{"type":"behavior","config":{"edited":true}}';

    /**
     * Edit the mocked Monaco textarea, which is the JSON view.
     *
     * `paste` rather than `type`: userEvent reads `{` as the opening of a key
     * descriptor (`{Enter}`, `{Shift}`), so typing raw JSON throws on the first
     * brace.
     */
    async function editJson(user: ReturnType<typeof userEvent.setup>) {
      const editor = screen.getByLabelText("JSON editor");
      await user.clear(editor);
      await user.click(editor);
      await user.paste(EDITED);
    }

    it("enables Save", async () => {
      const user = userEvent.setup();
      renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
      expect(screen.getByTestId("save-btn")).toBeDisabled();

      await editJson(user);

      expect(screen.getByTestId("save-btn")).toBeEnabled();
    });

    it("enables Discard", async () => {
      const user = userEvent.setup();
      renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
      expect(screen.getByTestId("discard-btn")).toBeDisabled();

      await editJson(user);

      expect(screen.getByTestId("discard-btn")).toBeEnabled();
    });

    it("shows the unsaved-changes indicator", async () => {
      const user = userEvent.setup();
      renderWithProviders(<ConfigEditorLayout {...defaultProps} />);
      expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();

      await editJson(user);

      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });

    it("locks the version picker, so switching version cannot silently drop the edit", async () => {
      const user = userEvent.setup();
      renderWithProviders(
        <ConfigEditorLayout
          {...defaultProps}
          versions={[{ version: 1 }, { version: 2 }]}
        />
      );
      expect(screen.getByTestId("version-picker")).toBeEnabled();

      await editJson(user);

      // This is a data-loss guard, not decoration: the picker refetches and
      // replaces `data`, so an enabled picker over unsaved edits discards them
      // with no prompt.
      expect(screen.getByTestId("version-picker")).toBeDisabled();
    });

    it("passes the edited document to onSave, not the original", async () => {
      const user = userEvent.setup();
      const onSave = vi.fn();
      renderWithProviders(
        <ConfigEditorLayout {...defaultProps} onSave={onSave} />
      );

      await editJson(user);
      await user.click(screen.getByTestId("save-btn"));

      expect(onSave).toHaveBeenCalledTimes(1);
      const saved = onSave.mock.calls[0]![0] as string;
      expect(JSON.parse(saved)).toMatchObject({ config: { edited: true } });
    });

    it("Discard restores the original and clears the dirty state", async () => {
      const user = userEvent.setup();
      renderWithProviders(<ConfigEditorLayout {...defaultProps} />);

      await editJson(user);
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();

      await user.click(screen.getByTestId("discard-btn"));
      // Discard is guarded by a confirmation dialog. Addressed by testid, not
      // by role+name: the toolbar button and the dialog's confirm button both
      // read "Discard", so a name query resolves to two elements.
      await user.click(screen.getByTestId("unsaved-confirm"));

      await waitFor(() => {
        expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();
      });
      expect(screen.getByTestId("save-btn")).toBeDisabled();
      expect(screen.getByLabelText("JSON editor")).toHaveValue(defaultProps.data);
    });
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
