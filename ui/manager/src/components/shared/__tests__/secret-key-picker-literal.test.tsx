import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { SecretKeyPicker } from "@eddi/app-kit/components/shared/secret-key-picker";
import { server } from "@eddi/app-kit/test/mocks/server";

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
    renderWithProviders(<SecretKeyPicker value="sk-live-abcdef123456" onChange={onChange} />);
    expect(screen.getByTestId("secret-key-picker-literal-nudge")).toBeInTheDocument();
    expect(screen.getByTestId("secret-key-picker-input")).not.toHaveAttribute("aria-invalid");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("makes keeping the literal a deliberate choice, then stops nudging", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="sk-live-abcdef123456" onChange={vi.fn()} />);
    await user.click(screen.getByTestId("secret-key-picker-literal-keep"));
    expect(screen.queryByTestId("secret-key-picker-literal-nudge")).not.toBeInTheDocument();
  });

  it("offers Store in vault, with the typed value already in the create dialog", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="sk-live-abcdef123456" onChange={vi.fn()} />);
    await user.click(screen.getByTestId("secret-key-picker-literal-vault"));
    expect(await screen.findByTestId("create-secret-modal-value")).toHaveValue("sk-live-abcdef123456");
    // No agent known here: the dialog says the secret is created for every agent.
    expect(screen.getByTestId("create-secret-modal-all-agents")).toBeInTheDocument();
  });

  it("narrows the grant to the owning agent when it is known, and says nothing about all agents", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="sk-live-abcdef123456" onChange={vi.fn()} agentId="agent-1" />);
    await user.click(screen.getByTestId("secret-key-picker-literal-vault"));
    await screen.findByTestId("create-secret-modal-value");
    expect(screen.queryByTestId("create-secret-modal-all-agents")).not.toBeInTheDocument();
  });

  it("does not nudge for references, placeholders or short values", () => {
    for (const value of ["${vault:openai-key}", "${vars:model-key}", "short", "two words here"]) {
      const { unmount } = renderWithProviders(<SecretKeyPicker value={value} onChange={vi.fn()} />);
      expect(screen.queryByTestId("secret-key-picker-literal-nudge")).not.toBeInTheDocument();
      unmount();
    }
  });
});
