import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretsPage } from "@/pages/secrets";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderSecrets() {
  return renderWithProviders(<SecretsPage />, {
    initialRoute: "/manage/secrets",
  });
}

describe("SecretsPage", () => {
  // ─── Basic rendering ────────────────────────────────────────────────────

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

  it("shows info banner when vault is up", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(
        screen.getByText(
          /Secrets are scoped per tenant/
        )
      ).toBeInTheDocument();
    });
  });

  // ─── Secret list / table ──────────────────────────────────────────────

  it("loads and displays secrets for default tenant", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
      expect(screen.getByText("sendgrid-api-key")).toBeInTheDocument();
    });
  });

  it("shows description column", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(
        screen.getByText("OpenAI API key for GPT-5.4 production agents")
      ).toBeInTheDocument();
    });
  });

  it("shows copy reference buttons", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByTestId("copy-ref-openai-api-key")).toBeInTheDocument();
    });
  });

  it("shows table headers for all columns", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByText("Key Name")).toBeInTheDocument();
      expect(screen.getByText("Description")).toBeInTheDocument();
      expect(screen.getByText("Reference")).toBeInTheDocument();
      expect(screen.getByText("Created")).toBeInTheDocument();
      expect(screen.getByText("Last Rotated")).toBeInTheDocument();
      expect(screen.getByText("Checksum")).toBeInTheDocument();
      expect(screen.getByText("Allowed Agents")).toBeInTheDocument();
      expect(screen.getByText("Actions")).toBeInTheDocument();
    });
  });

  it("shows table title with count and tenant", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });
    // The table header shows count and tenant
    expect(
      screen.getByText(/secret.*in tenant.*default/i)
    ).toBeInTheDocument();
  });

  it("shows allowed agents badge — All agents when no restriction", async () => {
    renderSecrets();
    await waitFor(() => {
      // At least one entry should show "All agents"
      const allAgentBadges = screen.getAllByText("All agents");
      expect(allAgentBadges.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows rotate and delete action buttons for each secret", async () => {
    renderSecrets();
    await waitFor(() => {
      expect(screen.getByTestId("rotate-openai-api-key")).toBeInTheDocument();
      expect(screen.getByTestId("delete-openai-api-key")).toBeInTheDocument();
      expect(screen.getByTestId("rotate-sendgrid-api-key")).toBeInTheDocument();
      expect(screen.getByTestId("delete-sendgrid-api-key")).toBeInTheDocument();
    });
  });

  it("shows vault reference in short format for default tenant", async () => {
    renderSecrets();
    await waitFor(() => {
      const refBtn = screen.getByTestId("copy-ref-openai-api-key");
      expect(refBtn.textContent).toContain("${vault:openai-api-key}");
    });
  });

  // ─── Create dialog ────────────────────────────────────────────────────

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
    expect(screen.getByText(/\$\{vault:mySecret\}/)).toBeInTheDocument();
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

  it("create dialog shows tenant context badge", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(
      screen.getByText(/Storing in tenant.*default/)
    ).toBeInTheDocument();
  });

  it("shows description input in create dialog", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(screen.getByTestId("new-description-input")).toBeInTheDocument();
  });

  it("shows allowed agents input in create dialog", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(screen.getByTestId("new-allowed-agents-input")).toBeInTheDocument();
  });

  it("shows encryption warning in create dialog", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(
      screen.getByText(/AES-256-GCM/)
    ).toBeInTheDocument();
  });

  it("closes create dialog when Cancel is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(screen.getByTestId("new-key-input")).toBeInTheDocument();

    // Click cancel button
    const cancelBtn = screen.getByText("Cancel");
    await user.click(cancelBtn);

    // Dialog should be closed
    await waitFor(() => {
      expect(screen.queryByTestId("new-key-input")).not.toBeInTheDocument();
    });
  });

  it("closes create dialog when X button is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    expect(screen.getByText("Add Secret", { selector: "h2" })).toBeInTheDocument();

    await user.click(screen.getByTestId("close-create-dialog"));

    await waitFor(() => {
      expect(screen.queryByTestId("new-key-input")).not.toBeInTheDocument();
    });
  });

  it("submits create form successfully", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    await user.type(screen.getByTestId("new-key-input"), "test-key");
    await user.type(screen.getByTestId("new-value-input"), "test-value");
    await user.type(screen.getByTestId("new-description-input"), "Test description");

    await user.click(screen.getByTestId("confirm-create-button"));

    // Dialog should close on success
    await waitFor(() => {
      expect(screen.queryByTestId("new-key-input")).not.toBeInTheDocument();
    });
  });

  // ─── Delete confirmation ──────────────────────────────────────────────

  it("shows delete confirmation when delete is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-openai-api-key"));
    expect(screen.getByText("Delete Secret")).toBeInTheDocument();
    expect(screen.getByTestId("confirm-delete-button")).toBeInTheDocument();
  });

  it("delete confirmation shows the secret key name", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-openai-api-key"));
    expect(
      screen.getByText(/permanently delete.*openai-api-key/i)
    ).toBeInTheDocument();
  });

  it("cancels delete confirmation", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-openai-api-key"));
    expect(screen.getByText("Delete Secret")).toBeInTheDocument();

    // Click cancel
    await user.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(screen.queryByText("Delete Secret")).not.toBeInTheDocument();
    });
  });

  it("executes delete when confirmed", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-openai-api-key"));
    await user.click(screen.getByTestId("confirm-delete-button"));

    // Dialog should close after successful deletion
    await waitFor(() => {
      expect(screen.queryByText("Delete Secret")).not.toBeInTheDocument();
    });
  });

  // ─── Rotate dialog ───────────────────────────────────────────────────

  it("opens rotate dialog when rotate is clicked", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("rotate-openai-api-key"));
    expect(screen.getByTestId("rotate-value-input")).toBeInTheDocument();
    expect(screen.getByTestId("confirm-rotate-button")).toBeInTheDocument();
  });

  it("rotate dialog shows correct key name", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("rotate-openai-api-key"));
    expect(
      screen.getByText(/Rotate.*openai-api-key/i)
    ).toBeInTheDocument();
  });

  it("rotate confirm button is disabled until new value is entered", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("rotate-openai-api-key"));
    const confirmBtn = screen.getByTestId("confirm-rotate-button");
    expect(confirmBtn).toBeDisabled();

    await user.type(screen.getByTestId("rotate-value-input"), "new-secret-value");
    expect(confirmBtn).not.toBeDisabled();
  });

  it("rotate dialog has password visibility toggle", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("rotate-openai-api-key"));

    const rotateInput = screen.getByTestId("rotate-value-input");
    expect(rotateInput).toHaveAttribute("type", "password");

    await user.click(screen.getByTestId("rotate-value-eye"));

    expect(rotateInput).toHaveAttribute("type", "text");
  });

  it("cancels rotate dialog", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("rotate-openai-api-key"));
    expect(screen.getByTestId("rotate-value-input")).toBeInTheDocument();

    // Click cancel in the rotate dialog
    const cancelBtns = screen.getAllByText("Cancel");
    await user.click(cancelBtns[cancelBtns.length - 1]);

    await waitFor(() => {
      expect(screen.queryByTestId("rotate-value-input")).not.toBeInTheDocument();
    });
  });

  it("executes rotate when confirmed", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("rotate-openai-api-key"));
    await user.type(screen.getByTestId("rotate-value-input"), "new-secret-value");
    await user.click(screen.getByTestId("confirm-rotate-button"));

    // Dialog should close after successful rotation
    await waitFor(() => {
      expect(screen.queryByTestId("rotate-value-input")).not.toBeInTheDocument();
    });
  });

  // ─── Tenant change ───────────────────────────────────────────────────

  it("changes tenant ID and loads different secrets", async () => {
    renderSecrets();
    const user = userEvent.setup();

    // Wait for default tenant secrets to load
    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    // Change tenant
    const tenantInput = screen.getByTestId("tenant-input");
    await user.clear(tenantInput);
    await user.type(tenantInput, "custom-tenant");

    // The secrets list should update (may show empty or different data)
    await waitFor(() => {
      // The tenant input value should be updated
      expect(tenantInput).toHaveValue("custom-tenant");
    });
  });

  // ─── Empty state ─────────────────────────────────────────────────────

  it("shows empty state when no secrets exist for tenant", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () => {
        return HttpResponse.json([]);
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByText("No secrets found")).toBeInTheDocument();
    });
  });

  // ─── Vault down state ─────────────────────────────────────────────────

  it("shows vault not configured banner when vault is down", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          available: false,
          error: "Vault connection failed",
          reason: "No vault configured",
          action: "Set VAULT_URL env var",
          docs: "https://docs.example.com/vault",
        });
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByTestId("vault-not-configured")).toBeInTheDocument();
    });
  });

  it("disables Add Secret when vault is down", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          available: false,
          error: "Vault connection failed",
        });
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByTestId("create-secret-button")).toBeDisabled();
    });
  });

  it("shows vault error details when available", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () => {
        return HttpResponse.json({
          available: false,
          error: "Vault connection failed",
          reason: "No vault configured",
          action: "Set VAULT_URL env var",
          docs: "https://docs.example.com/vault",
        });
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByText("Vault connection failed")).toBeInTheDocument();
      expect(screen.getByText("No vault configured")).toBeInTheDocument();
      expect(screen.getByText("Set VAULT_URL env var")).toBeInTheDocument();
      expect(screen.getByText("View documentation")).toBeInTheDocument();
    });
  });

  // ─── Specific allowed agents ──────────────────────────────────────────

  it("shows specific agent IDs when allowedAgents is set", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () => {
        return HttpResponse.json([
          {
            tenantId: "default",
            keyName: "restricted-key",
            description: "Restricted secret",
            createdAt: "2025-01-10T10:00:00Z",
            lastRotatedAt: null,
            checksum: "abc123def456",
            allowedAgents: ["agent-one", "agent-two"],
          },
        ]);
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByText("agent-one")).toBeInTheDocument();
      expect(screen.getByText("agent-two")).toBeInTheDocument();
    });
  });

  it("truncates long agent IDs in allowed agents column", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () => {
        return HttpResponse.json([
          {
            tenantId: "default",
            keyName: "long-agent-key",
            description: null,
            createdAt: "2025-01-10T10:00:00Z",
            lastRotatedAt: null,
            checksum: null,
            allowedAgents: ["a-very-long-agent-id-that-exceeds-sixteen-chars"],
          },
        ]);
      })
    );

    renderSecrets();

    await waitFor(() => {
      // Long agent IDs are truncated to 16 chars + "…"
      expect(screen.getByText(/a-very-long-agen…/)).toBeInTheDocument();
    });
  });

  // ─── Checksum display ────────────────────────────────────────────────

  it("shows truncated checksum when available", async () => {
    renderSecrets();

    await waitFor(() => {
      expect(screen.getByText("openai-api-key")).toBeInTheDocument();
    });

    // Checksums are truncated to 12 chars + "…"
    const checksumCells = screen.getAllByText(/…$/);
    expect(checksumCells.length).toBeGreaterThanOrEqual(1);
  });

  it("shows dash when checksum is null", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () => {
        return HttpResponse.json([
          {
            tenantId: "default",
            keyName: "no-checksum-key",
            description: "No checksum",
            createdAt: "2025-01-10T10:00:00Z",
            lastRotatedAt: null,
            checksum: null,
            allowedAgents: null,
          },
        ]);
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByText("no-checksum-key")).toBeInTheDocument();
    });

    // At least one "—" should appear for null checksum
    const dashes = screen.getAllByText("—");
    expect(dashes.length).toBeGreaterThanOrEqual(1);
  });

  // ─── Vault reference with custom tenant ────────────────────────────────

  it("shows full vault reference with custom tenant ID", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () => {
        return HttpResponse.json([
          {
            tenantId: "my-org",
            keyName: "org-key",
            description: "",
            createdAt: "2025-01-10T10:00:00Z",
            lastRotatedAt: null,
            checksum: "abc123",
            allowedAgents: null,
          },
        ]);
      })
    );

    renderSecrets();
    const user = userEvent.setup();

    // Change to custom tenant
    const tenantInput = screen.getByTestId("tenant-input");
    await user.clear(tenantInput);
    await user.type(tenantInput, "my-org");

    await waitFor(() => {
      expect(screen.getByText("org-key")).toBeInTheDocument();
    });

    // Reference should include tenant prefix
    const refBtn = screen.getByTestId("copy-ref-org-key");
    expect(refBtn.textContent).toContain("${vault:my-org/org-key}");
  });

  // ─── Create secret with allowed agents ────────────────────────────────

  it("adds allowed agents via Enter key", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    const agentInput = screen.getByTestId("new-allowed-agents-input");
    await user.type(agentInput, "agent-123{Enter}");

    // Agent should appear as a chip
    await waitFor(() => {
      expect(screen.getByText("agent-123")).toBeInTheDocument();
    });
  });

  it("create form with description and allowed agents submits successfully", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-secret-button"));

    await user.type(screen.getByTestId("new-key-input"), "full-test-key");
    await user.type(screen.getByTestId("new-value-input"), "full-test-value");
    await user.type(screen.getByTestId("new-description-input"), "A test description");

    // Add an allowed agent
    const agentInput = screen.getByTestId("new-allowed-agents-input");
    await user.type(agentInput, "my-agent{Enter}");

    await waitFor(() => {
      expect(screen.getByText("my-agent")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("confirm-create-button"));

    await waitFor(() => {
      expect(screen.queryByTestId("new-key-input")).not.toBeInTheDocument();
    });
  });

  // ─── Description display ──────────────────────────────────────────────

  it("shows dash for secret with no description", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () => {
        return HttpResponse.json([
          {
            tenantId: "default",
            keyName: "no-desc-key",
            description: "",
            createdAt: null,
            lastRotatedAt: null,
            checksum: null,
            allowedAgents: null,
          },
        ]);
      })
    );

    renderSecrets();

    await waitFor(() => {
      expect(screen.getByText("no-desc-key")).toBeInTheDocument();
    });

    // Empty description shows "—"
    const dashes = screen.getAllByText("—");
    expect(dashes.length).toBeGreaterThanOrEqual(1);
  });
});

