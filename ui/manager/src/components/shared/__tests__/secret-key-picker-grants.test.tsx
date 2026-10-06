import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretKeyPicker } from "../secret-key-picker";
import { AuthContext, GUEST_CONTEXT } from "@/components/auth/auth-context";

/**
 * The restricted-key badge and the "not granted yet" note.
 *
 * Driven by the stateful vault mock's seed: `google-gemini-key` is granted to
 * agent5 and agent7 only, `openai-api-key` to every agent.
 */
describe("SecretKeyPicker — restricted keys", () => {
  const noop = () => {};

  it("badges a restricted key with its agent count, and names the agents in the tooltip", async () => {
    renderWithProviders(<SecretKeyPicker value="${vault:google-gemini-key}" onChange={noop} />);
    const badge = await screen.findByTestId("secret-key-picker-restricted");
    expect(badge).toHaveTextContent("2");
    // The mock's agent list names agent5 / agent7; the tooltip lists the grant.
    await waitFor(() => expect(badge.getAttribute("title")).toMatch(/agent5|Agent/));
  });

  it("does not badge a key every agent may use", async () => {
    renderWithProviders(<SecretKeyPicker value="${vault:openai-api-key}" onChange={noop} />);
    // Wait until the vault list has loaded (the chip shows no "not found" warning then).
    await screen.findByText("openai-api-key");
    await waitFor(() => expect(screen.queryByTestId("secret-key-picker-restricted")).not.toBeInTheDocument());
  });

  it("badges restricted keys in the vault popup too", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={noop} />);
    await user.click(screen.getByTestId("secret-key-picker-vault-btn"));
    expect(await screen.findByTestId("vault-key-restricted-google-gemini-key")).toBeInTheDocument();
    expect(screen.queryByTestId("vault-key-restricted-openai-api-key")).not.toBeInTheDocument();
  });

  it("tells an admin the deploy will ask to add an agent that is not on the grant", async () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:google-gemini-key}" onChange={noop} agentId="agent1" />,
    );
    const note = await screen.findByTestId("secret-key-picker-grant-note");
    expect(note).toHaveTextContent(/asked to add it on deploy/i);
  });

  it("tells a non-admin to ask an administrator", async () => {
    renderWithProviders(
      <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-editor"] }}>
        <SecretKeyPicker value="${vault:google-gemini-key}" onChange={noop} agentId="agent1" />
      </AuthContext.Provider>,
    );
    const note = await screen.findByTestId("secret-key-picker-grant-note");
    expect(note).toHaveTextContent(/ask an administrator/i);
  });

  it("says nothing when the agent is already on the grant", async () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:google-gemini-key}" onChange={noop} agentId="agent5" />,
    );
    await screen.findByTestId("secret-key-picker-restricted");
    expect(screen.queryByTestId("secret-key-picker-grant-note")).not.toBeInTheDocument();
  });

  it("says nothing without an agentId — the caller did not say which agent this is", async () => {
    renderWithProviders(<SecretKeyPicker value="${vault:google-gemini-key}" onChange={noop} />);
    await screen.findByTestId("secret-key-picker-restricted");
    expect(screen.queryByTestId("secret-key-picker-grant-note")).not.toBeInTheDocument();
  });
});
