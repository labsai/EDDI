import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { OperatorActivation } from "../operator-activation";
import { extractVaultKeyName } from "@/lib/operator/vault-ref";
import { defaultOperatorConfig } from "@/lib/api/operator";

const authState = { method: "none" as "none" | "keycloak" };
vi.mock("@/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: [],
    method: authState.method,
    login: () => {},
    logout: () => {},
  }),
  useHasRole: () => true,
}));

function renderActivation(overrides: Partial<Parameters<typeof OperatorActivation>[0]> = {}) {
  const onActivate = vi.fn();
  renderWithProviders(
    <OperatorActivation
      initial={defaultOperatorConfig("Body text.")}
      stage="idle"
      error={null}
      onActivate={onActivate}
      {...overrides}
    />,
  );
  return { onActivate };
}

describe("OperatorActivation", () => {
  beforeEach(() => {
    authState.method = "none";
    server.resetHandlers();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  it("states plainly that the operator is read-only", () => {
    renderActivation();
    expect(screen.getAllByText(/read-only/i).length).toBeGreaterThan(0);
  });

  it("blocks the next step until a model key is supplied", async () => {
    renderActivation();
    expect(screen.getByTestId("operator-next")).toBeDisabled();

    await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
    await waitFor(() =>
      expect(screen.getByTestId("operator-next")).not.toBeDisabled(),
    );
  });

  it("does not require a key for a local provider, but does require a base URL", async () => {
    renderActivation();
    await userEvent.selectOptions(screen.getByTestId("operator-provider"), "ollama");

    // Local provider needs no key, so only the base URL gates progress.
    await waitFor(() => expect(screen.getByTestId("operator-base-url")).toBeInTheDocument());
    expect(screen.getByTestId("operator-next")).toBeDisabled();

    await userEvent.type(screen.getByTestId("operator-base-url"), "http://localhost:11434");
    await waitFor(() =>
      expect(screen.getByTestId("operator-next")).not.toBeDisabled(),
    );
  });

  it("warns when the vault is unavailable so the key step isn't silently unusable", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json(
          { status: "DOWN", provider: "local", available: false },
          { status: 503 },
        ),
      ),
    );
    renderActivation();
    expect(await screen.findByText(/secrets vault is unavailable/i)).toBeInTheDocument();
  });

  describe("auth mode gating", () => {
    it("allows the no-credentials mode when authentication is disabled", async () => {
      renderActivation();
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      expect(screen.queryByTestId("operator-auth-blocked")).not.toBeInTheDocument();

      await userEvent.click(screen.getByTestId("operator-next"));
      expect(await screen.findByTestId("operator-activate")).not.toBeDisabled();
    });

    it("blocks the no-credentials mode when OIDC is enabled", async () => {
      // Tool calls would carry no Authorization header and 401 on every lookup,
      // so the operator would deploy READY and then be useless.
      authState.method = "keycloak";
      renderActivation();
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");

      expect(await screen.findByTestId("operator-auth-blocked")).toBeInTheDocument();
      await userEvent.click(screen.getByTestId("operator-next"));
      expect(await screen.findByTestId("operator-activate")).toBeDisabled();
    });

    it("unblocks once caller-context is chosen and the token risk is acknowledged", async () => {
      authState.method = "keycloak";
      const { onActivate } = renderActivation();
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      await userEvent.click(screen.getByTestId("operator-auth-caller-context"));

      // The token-at-rest trade-off must be acknowledged, not just displayed.
      await userEvent.click(screen.getByTestId("operator-next"));
      expect(await screen.findByTestId("operator-activate")).toBeDisabled();

      await userEvent.click(screen.getByRole("button", { name: /back/i }));
      await userEvent.click(screen.getByTestId("operator-accept-token-risk"));
      await userEvent.click(screen.getByTestId("operator-next"));

      const activate = await screen.findByTestId("operator-activate");
      expect(activate).not.toBeDisabled();

      await userEvent.click(activate);
      expect(onActivate).toHaveBeenCalledTimes(1);
      expect(onActivate.mock.calls[0]![0]).toMatchObject({
        authMode: "caller-context",
        scope: "read_only",
      });
    });

    it("shows the token-at-rest warning whenever caller-context is selected", async () => {
      renderActivation();
      await userEvent.click(screen.getByTestId("operator-auth-caller-context"));
      expect(
        await screen.findByText(/stored in the conversation record/i),
      ).toBeInTheDocument();
    });
  });

  it("surfaces an activation error instead of failing silently", async () => {
    renderActivation({ error: "This EDDI deployment does not expose 2 endpoint(s)" });
    await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
    await userEvent.click(screen.getByTestId("operator-next"));
    expect(await screen.findByTestId("operator-activation-error")).toHaveTextContent(
      /does not expose 2 endpoint/i,
    );
  });

  it("shows which stage activation is in", async () => {
    renderActivation({ stage: "provisioning" });
    await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
    await userEvent.click(screen.getByTestId("operator-next"));
    expect(await screen.findByTestId("operator-activation-stage")).toBeInTheDocument();
  });
});

describe("extractVaultKeyName", () => {
  it("pulls the key name from the canonical reference", () => {
    expect(extractVaultKeyName("vault:openai-key")).toBe("openai-key");
    expect(extractVaultKeyName("${vault:openai-key}")).toBe("openai-key");
  });

  it("accepts the legacy prefix", () => {
    expect(extractVaultKeyName("${eddivault:openai-key}")).toBe("openai-key");
  });

  it("returns null for a plain-text secret, so no secret is stored as a 'key name'", () => {
    expect(extractVaultKeyName("sk-actual-secret-value")).toBeNull();
  });
});
