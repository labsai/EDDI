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

/** Select an agent from the dropdown (now a <select> instead of text input) */
async function selectAgent(user: ReturnType<typeof userEvent.setup>, agentId = "agent1") {
  // Wait for agent descriptors to load into the <select>
  const select = screen.getByTestId("agent-id-input");
  await waitFor(() => {
    expect(select.querySelectorAll("option").length).toBeGreaterThan(1);
  });
  await user.selectOptions(select, agentId);
}

describe("SecretsPage", () => {
  it("renders the page title and description", () => {
    renderSecrets();
    expect(screen.getByText("Secrets Vault")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Manage encrypted secrets stored in the vault. Values are never exposed."
      )
    ).toBeInTheDocument();
  });

  it("renders the secrets-page container", () => {
    renderSecrets();
    expect(screen.getByTestId("secrets-page")).toBeInTheDocument();
  });

  it("renders tenant and agent ID inputs", () => {
    renderSecrets();
    expect(screen.getByTestId("tenant-input")).toBeInTheDocument();
    expect(screen.getByTestId("agent-id-input")).toBeInTheDocument();
  });

  it("shows Enter an Agent ID prompt when no agent ID is set", () => {
    renderSecrets();
    expect(screen.getByText("Enter an Agent ID")).toBeInTheDocument();
  });

  it("disables Add Secret button when no agent ID", () => {
    renderSecrets();
    const btn = screen.getByTestId("create-secret-button");
    expect(btn).toBeDisabled();
  });

  it("enables Add Secret button after selecting agent", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await selectAgent(user);
    expect(screen.getByTestId("create-secret-button")).not.toBeDisabled();
  });

  it("loads and displays secrets after selecting agent and clicking refresh", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await selectAgent(user);
    await user.click(screen.getByTestId("refresh-button"));

    await waitFor(() => {
      expect(screen.getByText("apiKey")).toBeInTheDocument();
      expect(screen.getByText("dbPassword")).toBeInTheDocument();
    });
  });

  it("shows vault health badge", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByTestId("vault-health")).toBeInTheDocument();
      expect(screen.getByText("Vault Online")).toBeInTheDocument();
    });
  });

  it("opens create dialog when Add Secret is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await selectAgent(user);
    await user.click(screen.getByTestId("create-secret-button"));

    expect(screen.getByTestId("new-key-input")).toBeInTheDocument();
    expect(screen.getByTestId("new-value-input")).toBeInTheDocument();
    expect(screen.getByTestId("new-value-eye")).toBeInTheDocument();
  });

  it("create dialog has password input with autocomplete off", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await selectAgent(user);
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
    await selectAgent(user);
    await user.click(screen.getByTestId("create-secret-button"));

    const valueInput = screen.getByTestId("new-value-input");
    expect(valueInput).toHaveAttribute("type", "password");

    await user.click(screen.getByTestId("new-value-eye"));
    expect(valueInput).toHaveAttribute("type", "text");
  });

  it("confirm create button is disabled until key and value are entered", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await selectAgent(user);
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

    await selectAgent(user);
    await user.click(screen.getByTestId("refresh-button"));

    await waitFor(() => {
      expect(screen.getByText("apiKey")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-apiKey"));
    expect(screen.getByText("Delete Secret")).toBeInTheDocument();
    expect(screen.getByTestId("confirm-delete-button")).toBeInTheDocument();
  });
});
