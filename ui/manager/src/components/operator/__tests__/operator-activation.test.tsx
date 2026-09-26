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

  /**
   * The field whose absence cost a day: the operator's tools used to be
   * provisioned with `window.location.origin` — the BROWSER's address — with
   * nothing on any screen saying so. It is now visible, prefilled from the
   * server, and editable.
   */
  describe("platform base URL", () => {
    it("prefills the address the backend reports it can reach itself at", async () => {
      renderActivation();
      const field = screen.getByLabelText(/platform base url/i);
      await waitFor(() => expect(field).toHaveValue("http://127.0.0.1:7070"));
    });

    it("hands the prefilled address to onActivate rather than the browser's origin", async () => {
      const { onActivate } = renderActivation({
        initial: { ...defaultOperatorConfig("Body text."), credentialKey: "operator-llm-key" },
      });
      await waitFor(() =>
        expect(screen.getByLabelText(/platform base url/i)).toHaveValue("http://127.0.0.1:7070"),
      );
      await userEvent.click(screen.getByTestId("operator-next"));
      await userEvent.click(await screen.findByTestId("operator-activate"));
      expect(onActivate).toHaveBeenCalledWith(
        expect.objectContaining({ apiBaseUrl: "http://127.0.0.1:7070" }),
        expect.anything(),
        undefined,
      );
    });

    it("keeps an admin's own value instead of overwriting it with the server's", async () => {
      renderActivation();
      const field = screen.getByLabelText(/platform base url/i);
      await waitFor(() => expect(field).toHaveValue("http://127.0.0.1:7070"));
      await userEvent.clear(field);
      await userEvent.type(field, "https://eddi.svc.internal:8443");
      // The prefill effect must not fight the admin for the field.
      await waitFor(() => expect(field).toHaveValue("https://eddi.svc.internal:8443"));
    });

    /** A value baked into 22 resources should not be able to be a bare hostname. */
    it("blocks continuing on a value that cannot be a base URL", async () => {
      renderActivation({
        initial: { ...defaultOperatorConfig("Body text."), credentialKey: "operator-llm-key" },
      });
      const field = screen.getByLabelText(/platform base url/i);
      await waitFor(() => expect(field).toHaveValue("http://127.0.0.1:7070"));
      await userEvent.clear(field);
      await userEvent.type(field, "eddi.internal:7070");
      expect(await screen.findByTestId("operator-platform-base-url-invalid")).toBeInTheDocument();
      expect(screen.getByTestId("operator-next")).toBeDisabled();
    });

    it("says so when the deployment cannot report its own address", async () => {
      server.use(
        http.get("*/administration/operator/self-url", () =>
          HttpResponse.json({ message: "not found" }, { status: 404 }),
        ),
      );
      renderActivation();
      expect(
        await screen.findByTestId("operator-platform-base-url-unknown"),
      ).toBeInTheDocument();
    });

    /**
     * A reconfigure carries the stored address in, and it wins over the server's
     * answer. When the two disagree — the deployment moved port since — the admin
     * must be told, or the reconfigure re-provisions the old fault.
     */
    it("says when a carried-in address differs from what the server now reports", async () => {
      renderActivation({
        initial: { ...defaultOperatorConfig("Body text."), apiBaseUrl: "http://127.0.0.1:9090" },
      });
      expect(await screen.findByTestId("operator-platform-base-url-differs")).toHaveTextContent(
        "http://127.0.0.1:7070",
      );
      // The loopback note describes the SERVER's answer; it must not sit under a
      // field holding something else.
      expect(screen.queryByTestId("operator-platform-base-url-source")).not.toBeInTheDocument();
    });

    /** Unresolved is an answer, not an old backend: activation will not fall back. */
    it("says the server cannot determine its address, without promising a fallback", async () => {
      server.use(
        http.get("*/administration/operator/self-url", () =>
          HttpResponse.json({ baseUrl: null, source: "unresolved" }),
        ),
      );
      renderActivation();
      expect(await screen.findByTestId("operator-platform-base-url-unresolved")).toBeInTheDocument();
      expect(screen.queryByTestId("operator-platform-base-url-unknown")).not.toBeInTheDocument();
    });

    /**
     * A 403/500 is not "an old backend": activation rethrows it, so the form must
     * not promise the browser-origin fallback it will not perform.
     */
    it("reports a failed query distinctly, and promptly", async () => {
      server.use(
        http.get("*/administration/operator/self-url", () =>
          HttpResponse.json({ message: "boom" }, { status: 500 }),
        ),
      );
      renderActivation();
      expect(await screen.findByTestId("operator-platform-base-url-query-failed")).toBeInTheDocument();
      expect(screen.queryByTestId("operator-platform-base-url-unknown")).not.toBeInTheDocument();
    });

    it("rejects a base URL carrying a query, fragment or credentials", async () => {
      renderActivation({
        initial: { ...defaultOperatorConfig("Body text."), credentialKey: "operator-llm-key" },
      });
      const field = screen.getByLabelText(/platform base url/i);
      await waitFor(() => expect(field).toHaveValue("http://127.0.0.1:7070"));
      for (const bad of ["http://eddi:7070?tenant=x", "http://eddi:7070#frag", "http://user:pass@eddi:7070"]) {
        await userEvent.clear(field);
        await userEvent.type(field, bad);
        expect(await screen.findByTestId("operator-platform-base-url-invalid")).toBeInTheDocument();
      }
    });

    it("rejects a base URL carrying a path or trailing text", async () => {
      renderActivation({
        initial: { ...defaultOperatorConfig("Body text."), credentialKey: "operator-llm-key" },
      });
      const field = screen.getByLabelText(/platform base url/i);
      await waitFor(() => expect(field).toHaveValue("http://127.0.0.1:7070"));
      for (const bad of ["http://eddi:7070/eddi", "http://eddi:7070 extra"]) {
        await userEvent.clear(field);
        await userEvent.type(field, bad);
        expect(await screen.findByTestId("operator-platform-base-url-invalid")).toBeInTheDocument();
      }
      await userEvent.clear(field);
      await userEvent.type(field, "http://eddi:7070/");
      await waitFor(() =>
        expect(screen.queryByTestId("operator-platform-base-url-invalid")).not.toBeInTheDocument(),
      );
    });

    /** Empty is legal: activation resolves it. It must not block the form. */
    it("allows an empty value and reports it as server-resolved on review", async () => {
      renderActivation({
        initial: { ...defaultOperatorConfig("Body text."), credentialKey: "operator-llm-key" },
      });
      const field = screen.getByLabelText(/platform base url/i);
      await waitFor(() => expect(field).toHaveValue("http://127.0.0.1:7070"));
      await userEvent.clear(field);
      expect(screen.getByTestId("operator-next")).not.toBeDisabled();
      await userEvent.click(screen.getByTestId("operator-next"));
      expect(await screen.findByText(/resolved from the server/i)).toBeInTheDocument();
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

    it("explains the gate verification and background test write while read & write is selected", async () => {
      await toReviewStep();
      const warning = await screen.findByTestId("operator-scope-write-warning");
      // Honest about the new flow: the gate is verified before activation
      // finishes, the empirical test write runs in the background afterwards.
      expect(warning).toHaveTextContent(/verifies the approval gate/i);
      expect(warning).toHaveTextContent(/background/i);
      expect(warning).toHaveTextContent(/removed immediately/i);
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

describe("OperatorActivation — stored provider the setup flow no longer offers", () => {
  beforeEach(() => {
    authState.method = "none";
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  // An operator configured on gemini-vertex before it was hidden rendered a
  // select with no matching option: it showed one provider and held another.
  it("falls back to an offered provider with its default model and no carried key", () => {
    renderActivation({
      initial: {
        ...defaultOperatorConfig("Body text."),
        provider: "gemini-vertex",
        model: "gemini-2.5-flash",
        credentialKey: "vertex-key",
      },
    });

    const select = screen.getByTestId("operator-provider") as HTMLSelectElement;
    expect(select.value).toBe("anthropic");
    expect(screen.getByTestId("operator-model")).toHaveValue("claude-sonnet-5");
    expect(screen.getByTestId("operator-api-key-input")).toHaveValue("");
  });
});
