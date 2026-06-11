import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretKeyPicker } from "../secret-key-picker";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

describe("SecretKeyPicker", () => {
  const mockOnChange = vi.fn();

  beforeEach(() => {
    mockOnChange.mockReset();
    server.resetHandlers();

    // Default MSW handlers
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          status: "UP",
          provider: "local",
          available: true,
        });
      }),
      http.get("*/secretstore/secrets/default", () => {
        return HttpResponse.json([
          {
            tenantId: "default",
            keyName: "openai-key",
            createdAt: "2026-06-08T12:00:00Z",
            lastAccessedAt: null,
            lastRotatedAt: null,
            checksum: "abc",
            description: "OpenAI API Key",
            allowedAgents: ["*"],
          },
          {
            tenantId: "default",
            keyName: "slack-token",
            createdAt: "2026-06-08T12:00:00Z",
            lastAccessedAt: null,
            lastRotatedAt: null,
            checksum: "def",
            description: "Slack Bot Token",
            allowedAgents: ["*"],
          },
        ]);
      }),
      http.put("*/secretstore/secrets/default/:keyName", async ({ params }) => {
        const { keyName } = params;
        return HttpResponse.json({
          reference: `\${vault:${keyName}}`,
          tenantId: "default",
          keyName,
        });
      })
    );
  });

  it("renders input field in direct password mode when value is not a vault reference", () => {
    renderWithProviders(
      <SecretKeyPicker value="super-secret-plain-text" onChange={mockOnChange} />
    );

    const input = screen.getByTestId("secret-key-picker-input") as HTMLInputElement;
    expect(input).toBeInTheDocument();
    expect(input.type).toBe("password");
    expect(input.value).toBe("super-secret-plain-text");

    const vaultBtn = screen.getByTestId("secret-key-picker-vault-btn");
    expect(vaultBtn).toBeInTheDocument();
  });

  it("toggles password visibility when the eye button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="plain" onChange={mockOnChange} />);

    const input = screen.getByTestId("secret-key-picker-input") as HTMLInputElement;
    expect(input.type).toBe("password");

    const toggleBtn = screen.getByRole("button", { name: "Show" });
    await user.click(toggleBtn);
    expect(input.type).toBe("text");

    const toggleBtnHide = screen.getByRole("button", { name: "Hide" });
    await user.click(toggleBtnHide);
    expect(input.type).toBe("password");
  });

  it("renders chip in vault mode when value is a vault reference", () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:openai-key}" onChange={mockOnChange} />
    );

    // Should display the vault key chip
    expect(screen.getByText("openai-key")).toBeInTheDocument();
    
    // Clear button should be present
    expect(screen.getByTestId("secret-key-picker-clear")).toBeInTheDocument();
  });

  it("shows warning icon if the vault reference key does not exist in secrets", async () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:non-existent-key}" onChange={mockOnChange} />
    );

    // Wait for secrets listing to load and check warning icon presence
    await waitFor(() => {
      expect(screen.getByTitle("This key was not found in the vault")).toBeInTheDocument();
    });
  });

  it("triggers onChange with empty string when clear button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecretKeyPicker value="${vault:openai-key}" onChange={mockOnChange} />
    );

    const clearBtn = screen.getByTestId("secret-key-picker-clear");
    await user.click(clearBtn);

    expect(mockOnChange).toHaveBeenCalledWith("");
  });

  it("opens vault popup and displays list of secrets when vault button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    const vaultBtn = screen.getByTestId("secret-key-picker-vault-btn");
    await user.click(vaultBtn);

    // Should show popup and filter
    expect(screen.getByTestId("vault-popup")).toBeInTheDocument();
    
    // Wait for keys to load
    await screen.findByText("openai-key");
    await screen.findByText("slack-token");
    expect(screen.getByText("OpenAI API Key")).toBeInTheDocument();
  });

  it("filters secrets in the vault popup by keyName and description", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    const vaultBtn = screen.getByTestId("secret-key-picker-vault-btn");
    await user.click(vaultBtn);

    // Search filter input
    const filterInput = screen.getByTestId("vault-popup-filter");
    await user.type(filterInput, "slack");

    // Only slack should remain
    expect(screen.queryByText("openai-key")).not.toBeInTheDocument();
    expect(screen.getByText("slack-token")).toBeInTheDocument();
  });

  it("selects a secret from the popup list and updates value to vault reference", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    await user.click(screen.getByTestId("secret-key-picker-vault-btn"));
    const option = await screen.findByTestId("vault-key-openai-key");
    await user.click(option);

    expect(mockOnChange).toHaveBeenCalledWith("${vault:openai-key}");
  });

  it("opens create secret modal, handles inputs, and successfully calls store secret mutation", async () => {
    const user = userEvent.setup();
    let mutationPayload: Record<string, unknown> | null = null;

    server.use(
      http.put("*/secretstore/secrets/default/new-api-key", async ({ request }) => {
        mutationPayload = await request.json();
        return HttpResponse.json({
          reference: "${vault:new-api-key}",
          tenantId: "default",
          keyName: "new-api-key",
        });
      })
    );

    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    // Open popup
    await user.click(screen.getByTestId("secret-key-picker-vault-btn"));
    
    // Click Create secret
    const createBtn = screen.getByTestId("vault-popup-create");
    await user.click(createBtn);

    // Dialog should be present
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText("Add Secret")).toBeInTheDocument();

    // Fill form
    const dialog = screen.getByRole("dialog");
    const inputs = dialog.querySelectorAll("input");
    expect(inputs).toHaveLength(3);
    await user.type(inputs[0], "new-api-key");
    await user.type(inputs[1], "superval");
    await user.type(inputs[2], "Test API Key");

    // Store
    const storeBtn = screen.getByRole("button", { name: "Store Secret" });
    await user.click(storeBtn);

    await waitFor(() => {
      expect(mutationPayload).not.toBeNull();
      expect(mutationPayload.value).toBe("superval");
      expect(mutationPayload.description).toBe("Test API Key");
      expect(mockOnChange).toHaveBeenCalledWith("${vault:new-api-key}");
    });
  });

  it("handles vault health down gracefully", async () => {
    userEvent.setup();
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          status: "DOWN",
          provider: "local",
          available: false,
          reason: "Vault connection timeout",
        });
      })
    );

    renderWithProviders(<SecretKeyPicker value="" onChange={mockOnChange} />);

    // Wait for health check to run (health query is run on mount)
    await waitFor(() => {
      // The vault opener button should be hidden because health is down
      expect(screen.queryByTestId("secret-key-picker-vault-btn")).not.toBeInTheDocument();
    });
  });
});
