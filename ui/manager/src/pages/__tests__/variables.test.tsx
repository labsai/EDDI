import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { VariablesPage } from "@/pages/variables";
import userEvent from "@testing-library/user-event";

function renderVariables() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={["/manage/variables"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <VariablesPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("VariablesPage", () => {
  it("renders the page title and description", () => {
    renderVariables();
    expect(screen.getByText("Global Variables")).toBeInTheDocument();
    expect(
      screen.getByText(
        /Deployment-wide configuration values/,
      ),
    ).toBeInTheDocument();
  });

  it("renders the variables-page container", () => {
    renderVariables();
    expect(screen.getByTestId("variables-page")).toBeInTheDocument();
  });

  it("renders the Add Variable button", () => {
    renderVariables();
    expect(screen.getByTestId("create-variable-button")).toBeInTheDocument();
  });

  it("loads and displays mock variables in the table", async () => {
    renderVariables();
    await waitFor(() => {
      expect(screen.getByText("default-model")).toBeInTheDocument();
      expect(screen.getByText("fallback-model")).toBeInTheDocument();
      expect(screen.getByText("api.base-url")).toBeInTheDocument();
      expect(screen.getByText("llm.temperature")).toBeInTheDocument();
      expect(screen.getByText("branding.bot-name")).toBeInTheDocument();
      expect(screen.getByText("environment")).toBeInTheDocument();
    });
  });

  it("shows description column values", async () => {
    renderVariables();
    await waitFor(() => {
      expect(
        screen.getByText(/cascades to gpt-4\.1-mini on budget overflow/),
      ).toBeInTheDocument();
      // "environment" has description: null — should show em-dash
      expect(screen.getByText("Display name shown in chat UI and system prompts")).toBeInTheDocument();
    });
  });

  it("shows export status icons", async () => {
    renderVariables();
    await waitFor(() => {
      // default-model has exportable: true
      expect(screen.getByTestId("export-yes-default-model")).toBeInTheDocument();
      // api.base-url has exportable: false
      expect(screen.getByTestId("export-no-api.base-url")).toBeInTheDocument();
    });
  });

  it("shows copy reference buttons", async () => {
    renderVariables();
    await waitFor(() => {
      expect(screen.getByTestId("copy-vars-default-model")).toBeInTheDocument();
    });
  });

  it("opens create dialog when Add Variable is clicked", async () => {
    renderVariables();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-variable-button"));

    expect(screen.getByTestId("var-key-input")).toBeInTheDocument();
    expect(screen.getByTestId("var-value-input")).toBeInTheDocument();
    expect(screen.getByTestId("var-description-input")).toBeInTheDocument();
    expect(screen.getByTestId("var-exportable-checkbox")).toBeInTheDocument();
    expect(screen.getByText("Add Global Variable")).toBeInTheDocument();
  });

  it("key field is editable in create mode", async () => {
    renderVariables();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-variable-button"));

    const keyInput = screen.getByTestId("var-key-input");
    expect(keyInput).not.toBeDisabled();
  });

  it("shows key validation error for invalid characters", async () => {
    renderVariables();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-variable-button"));

    await user.type(screen.getByTestId("var-key-input"), "has space");
    expect(screen.getByTestId("key-error")).toBeInTheDocument();
  });

  it("confirm save button is disabled until key and value are entered", async () => {
    renderVariables();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-variable-button"));

    const confirmBtn = screen.getByTestId("confirm-save-button");
    expect(confirmBtn).toBeDisabled();

    await user.type(screen.getByTestId("var-key-input"), "my-var");
    await user.type(screen.getByTestId("var-value-input"), "my-value");
    expect(confirmBtn).not.toBeDisabled();
  });

  it("opens edit dialog with pre-filled values when edit is clicked", async () => {
    renderVariables();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("default-model")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("edit-default-model"));
    expect(screen.getByText("Edit Variable")).toBeInTheDocument();

    const keyInput = screen.getByTestId("var-key-input") as HTMLInputElement;
    expect(keyInput.value).toBe("default-model");
    expect(keyInput).toBeDisabled(); // key is read-only in edit mode

    const valueInput = screen.getByTestId("var-value-input") as HTMLInputElement;
    expect(valueInput.value).toBe("gpt-4.1");
  });

  it("shows delete confirmation when delete is clicked", async () => {
    renderVariables();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("default-model")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-default-model"));
    expect(screen.getByText("Delete Variable")).toBeInTheDocument();
    expect(screen.getByTestId("confirm-delete-button")).toBeInTheDocument();
  });

  it("filters variables when searching", async () => {
    renderVariables();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("default-model")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("variables-search"), "branding");

    // Should see branding.bot-name
    expect(screen.getByText("branding.bot-name")).toBeInTheDocument();
    // Should NOT see default-model
    expect(screen.queryByText("default-model")).not.toBeInTheDocument();
  });

  it("shows empty state when no variables exist", async () => {
    // Override the handler to return empty array for this test
    const { server } = await import("@/test/mocks/server");
    const { http, HttpResponse } = await import("msw");
    server.use(
      http.get("*/variablestore/variables/:tenantId", () => HttpResponse.json([])),
    );

    renderVariables();
    await waitFor(() => {
      expect(screen.getByTestId("variables-empty")).toBeInTheDocument();
      expect(screen.getByText("No global variables defined")).toBeInTheDocument();
    });
  });

  it("copies vars reference to clipboard when copy button is clicked", async () => {
    renderVariables();
    const user = userEvent.setup();

    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      writable: true,
      configurable: true,
    });

    await waitFor(() => {
      expect(screen.getByTestId("copy-vars-default-model")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("copy-vars-default-model"));
    expect(writeText).toHaveBeenCalledWith("${vars:default-model}");
  });

  it("info box displays usage syntax examples", () => {
    renderVariables();
    expect(
      screen.getByText(/\$\{vars:<key>\}/, { exact: false }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Changes take effect within 2 minutes/),
    ).toBeInTheDocument();
  });
});
