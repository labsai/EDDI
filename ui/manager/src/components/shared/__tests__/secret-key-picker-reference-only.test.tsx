import { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
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

  it("keeps the vault popup open once it moves focus into its own filter", async () => {
    // The popup focuses its filter 50 ms after opening. That blurred the input,
    // blur canonicalised the value into a chip, and the chip state renders no
    // popup — it vanished a moment after opening. The test above only passed
    // because it asserted before the timer fired, and failed under load.
    const user = userEvent.setup();
    renderWithProviders(<ControlledPicker initial="vault:jira-client-secret" referenceOnly />);

    await user.click(screen.getByTestId("secret-key-picker-input"));
    await user.click(await screen.findByTestId("secret-key-picker-vault-btn"));

    await waitFor(() =>
      expect(screen.getByTestId("vault-popup-filter")).toHaveFocus(),
    );
    expect(screen.getByTestId("vault-popup")).toBeInTheDocument();
  });

  // Keeping the popup open defers the input's blur, so every way OUT of the
  // popup must normalise instead — otherwise dismissing it leaves an unbraced
  // reference that fails the save until the field is focused and left again.
  describe("dismissing the popup still normalises an unbraced reference", () => {
    /**
     * Render a controlled picker holding `initial`, followed by a focusable
     * "next field" and some non-focusable page text, then open the vault popup
     * and wait until it has moved focus into its filter — the point at which
     * the input's blur has been deferred.
     */
    async function openPopupOn(initial: string) {
      const user = userEvent.setup();
      renderWithProviders(
        <>
          <ControlledPicker initial={initial} referenceOnly />
          <button type="button">next field</button>
          <p>page text</p>
        </>,
      );
      await user.click(screen.getByTestId("secret-key-picker-input"));
      await user.click(await screen.findByTestId("secret-key-picker-vault-btn"));
      await waitFor(() =>
        expect(screen.getByTestId("vault-popup-filter")).toHaveFocus(),
      );
      return user;
    }

    /**
     * The normalised chip is showing: the input is gone and so is the popup.
     * Checked by structure, not by the key's text alone — the popup lists the
     * same key as an option, so text on its own also matches a popup that
     * never closed and a value that was never normalised.
     */
    async function expectNormalisedChip(keyName: string) {
      await waitFor(() =>
        expect(screen.queryByTestId("secret-key-picker-input")).not.toBeInTheDocument(),
      );
      expect(screen.queryByTestId("vault-popup")).not.toBeInTheDocument();
      expect(screen.getByText(keyName)).toBeInTheDocument();
    }

    it("Escape returns focus to the input, which normalises once the user moves on", async () => {
      const user = await openPopupOn("vault:jira-client-secret");

      await user.keyboard("{Escape}");
      expect(screen.queryByTestId("vault-popup")).not.toBeInTheDocument();
      expect(screen.getByTestId("secret-key-picker-input")).toHaveFocus();

      await user.tab();
      await expectNormalisedChip("jira-client-secret");
    });

    it("clicking away from the popup normalises", async () => {
      const user = await openPopupOn("vault:jira-client-secret");

      // Non-focusable on purpose: focus falls to <body>, so the popup's blur
      // carries no relatedTarget and only the outside-mousedown path can
      // normalise. Clicking a button would pass through the tab-out path.
      await user.click(screen.getByText("page text"));

      await expectNormalisedChip("jira-client-secret");
    });

    it("tabbing out of the popup normalises", async () => {
      const user = await openPopupOn("vault:jira-client-secret");
      const nextField = screen.getByRole("button", { name: "next field" });

      // Through the popup's own controls and out the other side.
      for (let i = 0; i < 10 && !nextField.matches(":focus"); i++) {
        await user.tab();
      }

      expect(nextField).toHaveFocus();
      await expectNormalisedChip("jira-client-secret");
    });

    it("cancelling the create-secret dialog normalises rather than stranding the value", async () => {
      const user = await openPopupOn("vault:jira-client-secret");

      await user.click(screen.getByTestId("vault-popup-create"));
      await user.click(
        within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }),
      );

      await expectNormalisedChip("jira-client-secret");
    });

    it("keeps the newly created key when the dialog succeeds", async () => {
      // The trap in normalising on close: onSuccess runs first, but the parent
      // has not re-rendered, so a normalise on the way out would write the OLD
      // value over the key just created.
      const user = await openPopupOn("vault:jira-client-secret");

      await user.click(screen.getByTestId("vault-popup-create"));
      const dialog = within(await screen.findByRole("dialog"));
      await user.type(dialog.getByPlaceholderText(/openaiKey/), "brand-new-key");
      await user.type(dialog.getByPlaceholderText(/secret value/i), "s3cret");
      await user.click(dialog.getByRole("button", { name: "Store Secret" }));

      await expectNormalisedChip("brand-new-key");
      expect(screen.queryByText("jira-client-secret")).not.toBeInTheDocument();
    });

    it("picking a key is not overwritten by normalising the value it replaced", async () => {
      const user = await openPopupOn("vault:some-other-key");

      await user.click(await screen.findByTestId("vault-key-jira-client-secret"));

      await expectNormalisedChip("jira-client-secret");
      expect(screen.queryByText("some-other-key")).not.toBeInTheDocument();
    });
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
