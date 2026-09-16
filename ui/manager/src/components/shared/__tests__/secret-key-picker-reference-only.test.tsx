import { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretKeyPicker } from "../secret-key-picker";

/**
 * `referenceOnly` — the mode connections needs.
 *
 * Its backend refuses a plaintext secret in `oauth.clientSecret` and
 * `staticAuth.passwordRef` outright, so a field that accepts a pasted key and
 * fails on save is worse than useless: the 400 names a field the user can no
 * longer see. Kept in its own file so the default mode's own suite stays a
 * statement about the default mode.
 */

/** A host that actually holds the value, the way a real form does. */
function ControlledPicker({
  initial = "",
  referenceOnly = false,
}: {
  initial?: string;
  referenceOnly?: boolean;
}) {
  const [value, setValue] = useState(initial);
  return (
    <SecretKeyPicker value={value} onChange={setValue} referenceOnly={referenceOnly} />
  );
}

describe("SecretKeyPicker in reference-only mode", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    onChange.mockReset();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () =>
        HttpResponse.json([
          {
            tenantId: "default",
            keyName: "jira-client-secret",
            createdAt: "2026-06-08T12:00:00Z",
            lastAccessedAt: null,
            lastRotatedAt: null,
            checksum: "abc",
            description: "Jira OAuth client secret",
            allowedAgents: ["*"],
          },
        ]),
      ),
    );
  });

  it("shows a reference as a chip", () => {
    renderWithProviders(
      <SecretKeyPicker value="${vault:jira-client-secret}" onChange={onChange} referenceOnly />,
    );
    expect(screen.getByText("jira-client-secret")).toBeInTheDocument();
  });

  it("accepts a ${vars:…} reference, which the backend also accepts", () => {
    renderWithProviders(
      <SecretKeyPicker value="${vars:tenant-secret}" onChange={onChange} referenceOnly />,
    );
    // Keeps the scheme: "which global variable" is the whole content of the
    // value, and dropping it would look like a vault key that does not exist.
    expect(screen.getByText("vars:tenant-secret")).toBeInTheDocument();
    expect(
      screen.queryByTestId("secret-key-picker-literal-warning"),
    ).not.toBeInTheDocument();
  });

  it("warns about a literal instead of accepting it silently", () => {
    renderWithProviders(
      <SecretKeyPicker value="sk-live-abcdef" onChange={onChange} referenceOnly />,
    );
    expect(screen.getByTestId("secret-key-picker-literal-warning")).toBeInTheDocument();
    expect(screen.getByTestId("secret-key-picker-input")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });

  it("warns about an unbraced reference, which the backend's anchored pattern refuses", () => {
    renderWithProviders(
      <SecretKeyPicker value="vault:jira-client-secret" onChange={onChange} referenceOnly />,
    );
    // Not a chip: showing one would promise the field is fine and then fail the
    // save.
    expect(screen.getByTestId("secret-key-picker-literal-warning")).toBeInTheDocument();
  });

  it("does not mask the value — there is no secret in it to hide", () => {
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} referenceOnly />);
    expect(screen.getByTestId("secret-key-picker-input")).toHaveAttribute("type", "text");
  });

  it("leaves a half-typed reference alone", async () => {
    // The bug this pins: normalising on every keystroke turns `${vault:` into
    // `${vault:}` and the rest of the word lands after the closing brace.
    //
    // Needs a *controlled* host — with a spy for `onChange` the value never
    // advances, so every keystroke would arrive as a single character and the
    // corruption this guards against could not happen in the first place.
    const user = userEvent.setup();
    renderWithProviders(<ControlledPicker referenceOnly />);

    const input = screen.getByTestId("secret-key-picker-input");
    // `{{` is userEvent's escape for a literal brace.
    await user.type(input, "${{vault:jira");

    expect(input).toHaveValue("${vault:jira");
  });

  it("braces an unbraced reference on the way out of the field", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecretKeyPicker value="vault:jira-client-secret" onChange={onChange} referenceOnly />,
    );

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.tab();

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith("${vault:jira-client-secret}"),
    );
  });

  it("leaves a genuine literal alone on blur, so the warning still stands", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <SecretKeyPicker value="sk-live-abcdef" onChange={onChange} referenceOnly />,
    );

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.tab();

    expect(onChange).not.toHaveBeenCalled();
  });

  it("braces an unbraced ${vars:…} without rewriting its scheme", async () => {
    // The gap this closes: the picker's own scheme list was missing `vars`, so
    // a reference the backend accepts was refused by the field meant to help
    // write one — and the old canonicaliser would have mangled it into
    // `${vault:vars:…}` had it fired.
    const user = userEvent.setup();
    renderWithProviders(<ControlledPicker initial="vars:tenant-key" referenceOnly />);

    expect(screen.getByTestId("secret-key-picker-literal-warning")).toBeInTheDocument();
    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.tab();

    await waitFor(() =>
      expect(screen.getByText("vars:tenant-key")).toBeInTheDocument(),
    );
    expect(
      screen.queryByTestId("secret-key-picker-literal-warning"),
    ).not.toBeInTheDocument();
  });

  it("does not rewrite the value of a read-only field", async () => {
    // Every other mutating handler guards on readOnly; blur did not, so merely
    // tabbing through a locked field emitted a change and dirtied the form.
    const user = userEvent.setup();
    renderWithProviders(
      <SecretKeyPicker
        value="vault:jira-client-secret"
        onChange={onChange}
        referenceOnly
        readOnly
      />,
    );

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.tab();

    expect(onChange).not.toHaveBeenCalled();
  });

  it("describes the field with the warning, not just marks it invalid", async () => {
    renderWithProviders(
      <SecretKeyPicker value="sk-live-abcdef" onChange={onChange} referenceOnly />,
    );

    const input = screen.getByTestId("secret-key-picker-input");
    const warning = screen.getByTestId("secret-key-picker-literal-warning");
    // Without the association the field announces "invalid" and never says why.
    expect(warning).toHaveAttribute("id");
    expect(input.getAttribute("aria-describedby")).toContain(
      warning.getAttribute("id"),
    );
  });

  it("keeps its own invalid state when a caller passes aria-invalid={false}", () => {
    // `??` let an explicit false suppress the internally derived state, so the
    // one field that is actually wrong was the one "jump to first invalid"
    // skipped.
    renderWithProviders(
      <SecretKeyPicker
        value="sk-live-abcdef"
        onChange={onChange}
        referenceOnly
        aria-invalid={false}
      />,
    );

    expect(screen.getByTestId("secret-key-picker-input")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });

  it("opens the vault popup even when the click would canonicalise the value", async () => {
    // Blur used to canonicalise mid-click, swapping the input for a chip and
    // unmounting this very button between mousedown and mouseup.
    const user = userEvent.setup();
    renderWithProviders(<ControlledPicker initial="vault:jira-client-secret" referenceOnly />);

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.click(await screen.findByTestId("secret-key-picker-vault-btn"));

    expect(await screen.findByTestId("vault-popup")).toBeInTheDocument();
  });

  it("emits a canonical reference when a vault key is picked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} referenceOnly />);

    await user.click(await screen.findByTestId("secret-key-picker-vault-btn"));
    await user.click(await screen.findByTestId("vault-key-jira-client-secret"));

    expect(onChange).toHaveBeenCalledWith("${vault:jira-client-secret}");
  });
});

describe("SecretKeyPicker default mode is unchanged", () => {
  const onChange = vi.fn();

  beforeEach(() => onChange.mockReset());

  it("still masks a direct value and still offers the reveal toggle", () => {
    renderWithProviders(<SecretKeyPicker value="sk-live-abcdef" onChange={onChange} />);
    expect(screen.getByTestId("secret-key-picker-input")).toHaveAttribute(
      "type",
      "password",
    );
    expect(
      screen.queryByTestId("secret-key-picker-literal-warning"),
    ).not.toBeInTheDocument();
  });

  it("still renders a chip for the unbraced spellings it has always accepted", () => {
    renderWithProviders(<SecretKeyPicker value="vault:openai-key" onChange={onChange} />);
    expect(screen.getByText("openai-key")).toBeInTheDocument();
  });
});
