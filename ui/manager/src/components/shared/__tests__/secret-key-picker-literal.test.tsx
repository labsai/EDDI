import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { SecretKeyPicker } from "@eddi/app-kit/components/shared/secret-key-picker";
import { server } from "@eddi/app-kit/test/mocks/server";

// Assembled from parts so the made-up value does not look like a real key to secret scanners.
const FAKE_KEY = ["sk", "live", "abcdef123456"].join("-");

describe("SecretKeyPicker — a literal key typed into the field", () => {
  beforeEach(() => {
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  it("nudges towards the vault when the value looks like a pasted key, without blocking", async () => {
    const onChange = vi.fn();
    renderWithProviders(<SecretKeyPicker value={FAKE_KEY} onChange={onChange} />);
    expect(screen.getByTestId("secret-key-picker-literal-nudge")).toBeInTheDocument();
    expect(screen.getByTestId("secret-key-picker-input")).not.toHaveAttribute("aria-invalid");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("makes keeping the literal a deliberate choice, then stops nudging", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value={FAKE_KEY} onChange={vi.fn()} />);
    await user.click(screen.getByTestId("secret-key-picker-literal-keep"));
    expect(screen.queryByTestId("secret-key-picker-literal-nudge")).not.toBeInTheDocument();
  });

  it("binds 'Keep as plain text' to the value it was given for: another credential is asked about again", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<SecretKeyPicker value={FAKE_KEY} onChange={vi.fn()} />);
    await user.click(screen.getByTestId("secret-key-picker-literal-keep"));
    expect(screen.queryByTestId("secret-key-picker-literal-nudge")).not.toBeInTheDocument();

    // The credential is replaced, or another resource is loaded into the same picker.
    const OTHER_KEY = `${FAKE_KEY}-other`;
    rerender(<SecretKeyPicker value={OTHER_KEY} onChange={vi.fn()} />);
    expect(screen.getByTestId("secret-key-picker-literal-nudge")).toBeInTheDocument();

    // The value that was accepted stays accepted.
    rerender(<SecretKeyPicker value={FAKE_KEY} onChange={vi.fn()} />);
    expect(screen.queryByTestId("secret-key-picker-literal-nudge")).not.toBeInTheDocument();
  });

  it("offers Store in vault, with the typed value already in the create dialog", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value={FAKE_KEY} onChange={vi.fn()} />);
    await user.click(screen.getByTestId("secret-key-picker-literal-vault"));
    expect(await screen.findByTestId("create-secret-modal-value")).toHaveValue(FAKE_KEY);
    // No agent known here: the dialog says the secret is created for every agent.
    expect(screen.getByTestId("create-secret-modal-all-agents")).toBeInTheDocument();
  });

  it("narrows the grant to the owning agent when it is known, and says nothing about all agents", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value={FAKE_KEY} onChange={vi.fn()} agentId="agent-1" />);
    await user.click(screen.getByTestId("secret-key-picker-literal-vault"));
    await screen.findByTestId("create-secret-modal-value");
    expect(screen.queryByTestId("create-secret-modal-all-agents")).not.toBeInTheDocument();
  });

  it("creates the secret granted to the owning agent only", async () => {
    let stored: { allowedAgents?: string[] } | undefined;
    server.use(
      http.put("*/secretstore/secrets/:tenant/:key", async ({ request }) => {
        stored = (await request.json()) as typeof stored;
        return HttpResponse.json({ reference: "${vault:picker-key}" });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value={FAKE_KEY} onChange={vi.fn()} agentId="agent-1" />);
    await user.click(screen.getByTestId("secret-key-picker-literal-vault"));
    await user.type(await screen.findByTestId("create-secret-modal-key"), "picker-key");
    await user.click(screen.getByTestId("create-secret-modal-hosts-mode-any"));
    await user.click(screen.getByTestId("create-secret-modal-submit"));
    await vi.waitFor(() => expect(stored).toBeDefined());
    expect(stored!.allowedAgents).toEqual(["agent-1"]);
  });

  it("does not nudge for references, placeholders or short values", () => {
    for (const value of ["${vault:openai-key}", "${vars:model-key}", "short", "two words here"]) {
      const { unmount } = renderWithProviders(<SecretKeyPicker value={value} onChange={vi.fn()} />);
      expect(screen.queryByTestId("secret-key-picker-literal-nudge")).not.toBeInTheDocument();
      unmount();
    }
  });
});
