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
        scope: "read_only",
      });
    });

    it("explains what caller-identity does when it is selected", async () => {
      renderActivation();
      await userEvent.click(screen.getByTestId("operator-auth-caller-identity"));
      expect(await screen.findByText(/never stored/i)).toBeInTheDocument();
    });
  });

  describe("write scope selection", () => {
    const verifiedGate = { verified: true, checkedVersions: [1] };
    const unverifiedGate = { verified: false, reason: "toolApprovals.requireApproval is empty", checkedVersions: [1] };

    /** Gets to the review step with caller-identity auth (the other precondition). */
    async function toReviewStepWithCallerIdentity(overrides: Parameters<typeof renderActivation>[0] = {}) {
      const rendered = renderActivation(overrides);
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      await userEvent.click(screen.getByTestId("operator-auth-caller-identity"));
      await userEvent.click(screen.getByTestId("operator-next"));
      await screen.findByTestId("operator-activate");
      return rendered;
    }

    it("is disabled on first activation — nothing has verified the gate yet", async () => {
      await toReviewStepWithCallerIdentity();
      expect(screen.getByTestId("operator-scope-read_write")).toBeDisabled();
      expect(await screen.findByTestId("operator-scope-unavailable")).toHaveTextContent(/first activation/i);
    });

    it("is disabled when reconfiguring an operator whose gate is not verified", async () => {
      await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig("Body."), agentId: "op-1", version: 1 },
        gate: unverifiedGate,
      });
      expect(screen.getByTestId("operator-scope-read_write")).toBeDisabled();
      expect(await screen.findByTestId("operator-scope-unavailable")).toHaveTextContent(/not been verified/i);
    });

    it("is disabled without caller-identity auth, even with a verified gate", async () => {
      renderActivation({
        initial: { ...defaultOperatorConfig("Body."), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });
      await userEvent.type(screen.getByTestId("operator-api-key-input"), "sk-test-key");
      // authMode defaults to "none" — deliberately not switching it here.
      await userEvent.click(screen.getByTestId("operator-next"));
      await screen.findByTestId("operator-activate");
      expect(screen.getByTestId("operator-scope-read_write")).toBeDisabled();
    });

    it("is selectable once the gate is verified AND auth is caller-identity — both preconditions together", async () => {
      const { onActivate } = await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig("Body."), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });
      expect(screen.getByTestId("operator-scope-read_write")).not.toBeDisabled();
      expect(screen.queryByTestId("operator-scope-unavailable")).not.toBeInTheDocument();

      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      expect(await screen.findByTestId("operator-scope-write-warning")).toHaveTextContent(/write canary/i);

      await userEvent.click(screen.getByTestId("operator-activate"));
      expect(onActivate.mock.calls[0]![0]).toMatchObject({ scope: "read_write" });
    });

    it("switches the safety rules and the tool count when scope changes", async () => {
      await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig("Body."), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });
      const toolsLabelBefore = screen.getByText(/tools it will be given/i).textContent;

      await userEvent.click(screen.getByTestId("operator-scope-read_write"));

      expect(screen.getByText(/tools it will be given/i).textContent).not.toBe(toolsLabelBefore);
      // The write-gated preamble replaces "You are read-only" with the
      // approval-bound rules — see system-prompt.ts.
      expect(screen.queryByText(/you are read-only/i)).not.toBeInTheDocument();
    });

    it("swaps the default prompt body to match scope, but preserves a custom edit", async () => {
      // No arg: promptBody seeds to the REAL read_only default, not a fixed
      // "Body." literal — the "untouched, so swap it" comparison this test
      // exercises only ever matches a real default, never a fixture stub.
      await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig(), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });

      const promptBody = () => (screen.getByTestId("operator-prompt-body") as HTMLTextAreaElement).value;

      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      expect(promptBody()).toContain("When you change something");

      // Flip back — untouched, so it reverts to read_only's own default.
      await userEvent.click(screen.getByTestId("operator-scope-read_only"));
      expect(promptBody()).not.toContain("When you change something");

      // Now customize while on read_only, then flip to read_write — the
      // customization must survive, not be silently discarded.
      await userEvent.clear(screen.getByTestId("operator-prompt-body"));
      await userEvent.type(screen.getByTestId("operator-prompt-body"), "Custom instructions.");
      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      expect(screen.getByTestId("operator-prompt-body")).toHaveValue("Custom instructions.");
    });

    it("reverts to read_only when auth mode is changed away from caller-identity after read_write was chosen", async () => {
      const { onActivate } = await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig("Body."), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });
      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      expect(screen.getByTestId("operator-scope-read_write")).toBeChecked();

      await userEvent.click(screen.getByRole("button", { name: /^back$/i }));
      await userEvent.click(screen.getByTestId("operator-auth-none"));
      await userEvent.click(screen.getByTestId("operator-next"));

      // effectiveScope has silently reverted — the radio must visibly show
      // it (not just be disabled while still drawn as checked), and
      // activating now must not submit a write grant the precondition no
      // longer holds for.
      const readWriteRadio = screen.getByTestId("operator-scope-read_write");
      expect(readWriteRadio).toBeDisabled();
      expect(readWriteRadio).not.toBeChecked();
      expect(screen.getByTestId("operator-scope-read_only")).toBeChecked();
      await userEvent.click(screen.getByTestId("operator-activate"));
      expect(onActivate.mock.calls[0]![0]).toMatchObject({ scope: "read_only" });
    });

    it("re-syncs the prompt body when scope reverts indirectly, not only on an explicit pick", async () => {
      // handleScopeChange fires only when the radio is clicked. effectiveScope
      // also moves on its own when authMode stops being caller-identity — and
      // without the effect, the submitted config pairs read_only endpoints with
      // a body telling the agent it can create groups and change things.
      const { onActivate } = await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig(), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });
      const promptBody = () => (screen.getByTestId("operator-prompt-body") as HTMLTextAreaElement).value;

      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      expect(promptBody()).toContain("When you change something");

      await userEvent.click(screen.getByRole("button", { name: /^back$/i }));
      await userEvent.click(screen.getByTestId("operator-auth-none"));
      await userEvent.click(screen.getByTestId("operator-next"));

      expect(promptBody()).not.toContain("When you change something");
      await userEvent.click(screen.getByTestId("operator-activate"));
      const submitted = onActivate.mock.calls[0]![0];
      expect(submitted).toMatchObject({ scope: "read_only" });
      expect(submitted.promptBody).not.toContain("You can create an agent GROUP");
    });

    it("does not overwrite a customized prompt body when scope reverts indirectly", async () => {
      // The other half of the contract: an admin who edited the text keeps it,
      // exactly as an explicit scope flip already guarantees.
      await toReviewStepWithCallerIdentity({
        initial: { ...defaultOperatorConfig(), agentId: "op-1", version: 1 },
        gate: verifiedGate,
      });
      await userEvent.click(screen.getByTestId("operator-scope-read_write"));
      await userEvent.clear(screen.getByTestId("operator-prompt-body"));
      await userEvent.type(screen.getByTestId("operator-prompt-body"), "My own wording.");

      await userEvent.click(screen.getByRole("button", { name: /^back$/i }));
      await userEvent.click(screen.getByTestId("operator-auth-none"));
      await userEvent.click(screen.getByTestId("operator-next"));

      expect(screen.getByTestId("operator-prompt-body")).toHaveValue("My own wording.");
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
