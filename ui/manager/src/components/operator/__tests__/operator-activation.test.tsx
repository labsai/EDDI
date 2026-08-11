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

  // Regression guard: every control was previously anonymous to assistive
  // tech — a bare <label> with no htmlFor next to an id-less control.
  describe("accessibility", () => {
    it("gives every native control an accessible name", () => {
      renderActivation();
      expect(screen.getByLabelText(/^provider$/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/^model$/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/^environment$/i)).toBeInTheDocument();
    });

    it("names the composite credential and auth-mode controls", () => {
      renderActivation();
      expect(
        screen.getByRole("group", { name: /model api key/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("radiogroup", { name: /how the operator authenticates/i }),
      ).toBeInTheDocument();
    });

    it("announces activation progress", async () => {
      renderActivation({ stage: "provisioning" });
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      await userEvent.click(screen.getByTestId("operator-next"));
      const stage = await screen.findByTestId("operator-activation-stage");
      expect(stage).toHaveAttribute("aria-live", "polite");
    });
  });

  describe("reconfiguring an existing operator", () => {
    it("pre-fills the stored vault key so the credential need not be re-entered", async () => {
      renderActivation({
        initial: {
          ...defaultOperatorConfig("Body text."),
          provider: "anthropic",
          credentialKey: "operator-llm-key",
        },
      });
      // Ready to continue without touching the key field.
      await waitFor(() =>
        expect(screen.getByTestId("operator-next")).not.toBeDisabled(),
      );
    });

    it("warns that saving replaces the existing agent", async () => {
      renderActivation({
        initial: {
          ...defaultOperatorConfig("Body text."),
          agentId: "op-1",
          version: 1,
          credentialKey: "operator-llm-key",
        },
      });
      await userEvent.click(await screen.findByTestId("operator-next"));
      // setup-api only creates, so reconfiguring is not an in-place edit.
      expect(await screen.findByTestId("operator-rebuild-warning")).toBeInTheDocument();
    });

    it("does not warn about a rebuild on first activation", async () => {
      renderActivation();
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      await userEvent.click(screen.getByTestId("operator-next"));
      await screen.findByTestId("operator-activate");
      expect(screen.queryByTestId("operator-rebuild-warning")).not.toBeInTheDocument();
    });

    it("clears the key when the provider changes, since keys are provider-specific", async () => {
      renderActivation({
        initial: {
          ...defaultOperatorConfig("Body text."),
          provider: "anthropic",
          credentialKey: "operator-llm-key",
        },
      });
      await userEvent.selectOptions(screen.getByTestId("operator-provider"), "openai");
      await waitFor(() =>
        expect(screen.getByTestId("operator-next")).toBeDisabled(),
      );
    });
  });

  it("shows the write-gated posture up front — the default scope chip reads Read & write", () => {
    renderActivation();
    expect(screen.getByTestId("operator-scope-chip")).toHaveTextContent(/read & write/i);
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

    it("unblocks once caller-identity is chosen", async () => {
      authState.method = "keycloak";
      const { onActivate } = renderActivation();
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      await userEvent.click(screen.getByTestId("operator-auth-caller-identity"));

      // No acknowledgement to click: EDDI resolves ${caller:token} server-side,
      // so nothing about the token is persisted for the admin to accept.
      await userEvent.click(screen.getByTestId("operator-next"));
      const activate = await screen.findByTestId("operator-activate");
      expect(activate).not.toBeDisabled();

      await userEvent.click(activate);
      expect(onActivate).toHaveBeenCalledTimes(1);
      expect(onActivate.mock.calls[0]![0]).toMatchObject({
        authMode: "caller-identity",
        scope: "read_write",
      });
    });

    it("explains what caller-identity does when it is selected", async () => {
      renderActivation();
      await userEvent.click(screen.getByTestId("operator-auth-caller-identity"));
      expect(await screen.findByText(/never stored/i)).toBeInTheDocument();
    });
  });

  describe("write scope selection", () => {
    /** Gets to the review step, where the scope choice lives. */
    async function toReviewStep(overrides: Parameters<typeof renderActivation>[0] = {}) {
      const rendered = renderActivation(overrides);
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      await userEvent.click(screen.getByTestId("operator-next"));
      await screen.findByTestId("operator-activate");
      return rendered;
    }

    it("defaults to read & write on FIRST activation — no bootstrap, no prior verification demanded", async () => {
      // The old two-step ("activate read-only first, reconfigure later") gated
      // the OFFER on facts activation now proves about the agent it actually
      // creates: the gate is read back from the new document and the write
      // canary refuses to leave a write-capable operator deployed unless a
      // real write provably paused. So the choice is the admin's, up front.
      const { onActivate } = await toReviewStep();
      const readWrite = screen.getByTestId("operator-scope-read_write");
      expect(readWrite).not.toBeDisabled();
      expect(readWrite).toBeChecked();

      await userEvent.click(screen.getByTestId("operator-activate"));
      expect(onActivate.mock.calls[0]![0]).toMatchObject({ scope: "read_write" });
    });

    it("explains the write canary while read & write is selected", async () => {
      await toReviewStep();
      expect(await screen.findByTestId("operator-scope-write-warning")).toHaveTextContent(/write canary/i);
    });

    it("can be opted down to read-only, and submits that choice", async () => {
      const { onActivate } = await toReviewStep();
      await userEvent.click(screen.getByTestId("operator-scope-read_only"));
      expect(screen.getByTestId("operator-scope-read_only")).toBeChecked();
      expect(screen.queryByTestId("operator-scope-write-warning")).not.toBeInTheDocument();

      await userEvent.click(screen.getByTestId("operator-activate"));
      expect(onActivate.mock.calls[0]![0]).toMatchObject({ scope: "read_only" });
    });

    it("keeps a stored read_only choice when reconfiguring — opting down is remembered, not reset", async () => {
      const { onActivate } = await toReviewStep({
        initial: { ...defaultOperatorConfig("Body."), scope: "read_only", agentId: "op-1", version: 1 },
      });
      expect(screen.getByTestId("operator-scope-read_only")).toBeChecked();
      await userEvent.click(screen.getByTestId("operator-activate"));
      expect(onActivate.mock.calls[0]![0]).toMatchObject({ scope: "read_only" });
    });

    it("switches the safety rules and the tool count when scope changes", async () => {
      await toReviewStep();
      const toolsLabelBefore = screen.getByText(/tools it will be given/i).textContent;
      // Write default: the preamble carries the approval-bound rules.
      expect(screen.queryByText(/you are read-only/i)).not.toBeInTheDocument();

      await userEvent.click(screen.getByTestId("operator-scope-read_only"));

      expect(screen.getByText(/tools it will be given/i).textContent).not.toBe(toolsLabelBefore);
      // Read-only restores the read-only preamble — see system-prompt.ts.
      expect(screen.getByText(/you are read-only/i)).toBeInTheDocument();
    });

    it("swaps the default prompt body to match scope, but preserves a custom edit", async () => {
      // No arg: promptBody seeds to the REAL scope default, not a fixed
      // "Body." literal — the "untouched, so swap it" comparison this test
      // exercises only ever matches a real default, never a fixture stub.
      await toReviewStep({
        initial: { ...defaultOperatorConfig(), agentId: "op-1", version: 1 },
      });

      const promptBody = () => (screen.getByTestId("operator-prompt-body") as HTMLTextAreaElement).value;

      // Default scope is read_write, so the write guidance is present.
      expect(promptBody()).toContain("When you change something");

      // Flip down — untouched, so it swaps to read_only's own default.
      await userEvent.click(screen.getByTestId("operator-scope-read_only"));
      expect(promptBody()).not.toContain("When you change something");

      // Now customize while on read_only, then flip back to read_write — the
      // customization must survive, not be silently discarded.
      await userEvent.clear(screen.getByTestId("operator-prompt-body"));
      await userEvent.type(screen.getByTestId("operator-prompt-body"), "Custom instructions.");
      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      expect(screen.getByTestId("operator-prompt-body")).toHaveValue("Custom instructions.");
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
