import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { SecretsPage } from "@/pages/secrets";
import userEvent from "@testing-library/user-event";

function renderSecrets() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={["/manage/secrets"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <SecretsPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

describe("SecretsPage", () => {
  it("renders the page title and description", () => {
    renderSecrets();
    expect(screen.getByText("Secrets Vault")).toBeInTheDocument();
    expect(
      screen.getByText(
        /Manage encrypted secrets shared across all agents/
      )
    ).toBeInTheDocument();
  });

  it("renders the secrets-page container", () => {
    renderSecrets();
    expect(screen.getByTestId("secrets-page")).toBeInTheDocument();
  });

  it("renders tenant input", () => {
    renderSecrets();
    expect(screen.getByTestId("tenant-input")).toBeInTheDocument();
  });

  it("Add Secret button is enabled when vault is UP", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByTestId("create-secret-button")).not.toBeDisabled();
    });
  });

  it("loads and displays secrets for default tenant", async () => {
    renderSecrets();
    // Secrets auto-load for "default" tenant
    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
      expect(screen.getByText("sendgrid-api-key")).toBeInTheDocument();
    });
  });

  it("shows description column", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(
        screen.getByText("OpenAI API key for production agents")
      ).toBeInTheDocument();
    });
  });

  it("shows copy reference buttons", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByTestId("copy-ref-openai-api-key")).toBeInTheDocument();
    });
  });

  it("opens create dialog when Add Secret is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(screen.getByTestId("new-key-input")).toBeInTheDocument();
    expect(screen.getByTestId("new-value-input")).toBeInTheDocument();
    expect(screen.getByTestId("new-value-eye")).toBeInTheDocument();
    expect(screen.getByTestId("new-description-input")).toBeInTheDocument();
  });

  it("create dialog has password input with autocomplete off", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    const keyInput = screen.getByTestId("new-key-input");
    const valueInput = screen.getByTestId("new-value-input");
    expect(keyInput).toHaveAttribute("autocomplete", "off");
    expect(valueInput).toHaveAttribute("autocomplete", "new-password");
    expect(valueInput).toHaveAttribute("type", "password");
  });

  it("eye toggle reveals secret value", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    const valueInput = screen.getByTestId("new-value-input");
    expect(valueInput).toHaveAttribute("type", "password");

    await user.click(screen.getByTestId("new-value-eye"));
    expect(valueInput).toHaveAttribute("type", "text");
  });

  it("shows reference preview when key name is entered", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    await user.type(screen.getByTestId("new-key-input"), "mySecret");
    expect(screen.getByText(/\$\{eddivault:mySecret\}/)).toBeInTheDocument();
  });

  it("confirm create button is disabled until key and value are entered", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    const confirmBtn = screen.getByTestId("confirm-create-button");
    expect(confirmBtn).toBeDisabled();

    await user.type(screen.getByTestId("new-key-input"), "mySecret");
    await user.type(screen.getByTestId("new-value-input"), "myValue");
    expect(confirmBtn).not.toBeDisabled();
  });

  it("shows delete confirmation when delete is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();

    // Secrets auto-load for default tenant
    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-openai-api-key"));
    expect(screen.getByText("Delete Secret")).toBeInTheDocument();
    expect(screen.getByTestId("confirm-delete-button")).toBeInTheDocument();
  });
});
