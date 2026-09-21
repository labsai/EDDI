import { useState } from "react";
import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { HeaderValueField } from "@/components/connections/header-value-field";

/**
 * The STATIC header value, and the flip that made it unusable.
 *
 * The mode used to be `raw || !splittable`, recomputed from the live value — so
 * the field changed shape while being typed into and dropped the caret after
 * every first character.
 */

function Host({ initial = "" }: { initial?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <HeaderValueField value={value} onChange={setValue} />
      <span data-testid="value">{value}</span>
    </>
  );
}

describe("HeaderValueField", () => {
  it("opens an empty field in the guided view", () => {
    renderWithProviders(<Host />);
    expect(screen.getByTestId("header-value-prefix")).toBeInTheDocument();
    expect(screen.queryByTestId("header-value-raw")).not.toBeInTheDocument();
  });

  it("keeps focus in the prefix box while it is typed into", async () => {
    // One keystroke used to produce "B", which is not a splittable template, so
    // the guided grid unmounted and the raw input replaced it.
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    const prefix = screen.getByTestId("header-value-prefix");
    await user.type(prefix, "Bearer ");

    expect(prefix).toHaveValue("Bearer ");
    expect(prefix).toHaveFocus();
    expect(screen.queryByTestId("header-value-raw")).not.toBeInTheDocument();
  });

  it("keeps the guided view while a reference is typed character by character", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    const secret = screen.getByTestId("header-value-secret-input");
    await user.type(secret, "vault:jira");

    expect(screen.getByTestId("header-value-prefix")).toBeInTheDocument();
    expect(screen.queryByTestId("header-value-raw")).not.toBeInTheDocument();
  });

  it("composes the prefix and the reference into one template", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("header-value-prefix"), "Bearer ");
    await user.type(
      screen.getByTestId("header-value-secret-input"),
      "${{vault:jira-token}",
    );

    await waitFor(() =>
      expect(screen.getByTestId("value")).toHaveTextContent(
        "Bearer ${vault:jira-token}",
      ),
    );
  });

  it("opens a template it cannot split in the raw editor", () => {
    renderWithProviders(<Host initial="${vault:a} and ${vault:b}" />);
    expect(screen.getByTestId("header-value-raw")).toBeInTheDocument();
    expect(screen.queryByTestId("header-value-prefix")).not.toBeInTheDocument();
  });

  it("does not offer the guided view for a template it cannot express", () => {
    // Switching would have to throw part of the template away.
    renderWithProviders(<Host initial="${vault:a} and ${vault:b}" />);
    expect(screen.queryByTestId("header-value-toggle")).not.toBeInTheDocument();
  });

  it("stays in the raw editor once chosen, even as the value becomes splittable", async () => {
    // The reverse flip: typing the closing brace used to yank the user back to
    // the guided view mid-word.
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.click(screen.getByTestId("header-value-toggle"));
    const raw = screen.getByTestId("header-value-raw");
    await user.type(raw, "Bearer ${{vault:k}");

    expect(raw).toHaveFocus();
    expect(screen.getByTestId("header-value-raw")).toBeInTheDocument();
  });

  it("re-splits when the value changes from outside", async () => {
    // A version switch or a form reset is the one case where the component's
    // idea of the value is genuinely stale.
    const { rerender } = renderWithProviders(
      <HeaderValueField value="" onChange={() => {}} />,
    );
    rerender(
      <HeaderValueField value="Bearer ${vault:new-token}" onChange={() => {}} />,
    );

    await waitFor(() =>
      expect(screen.getByTestId("header-value-prefix")).toHaveValue("Bearer "),
    );
  });

  it("re-splits the guided fields when returning from the raw editor", async () => {
    // The guided fields only track the value while they own it: raw edits go
    // out through `emit`, which marks them as ours, so the re-split effect
    // deliberately ignores them. Without a re-split on the way back, the fields
    // showed the pre-raw pair and the next guided keystroke emitted *that* —
    // silently reverting the raw edit.
    const user = userEvent.setup();
    renderWithProviders(<Host initial="Bearer ${vault:old}" />);

    await user.click(screen.getByTestId("header-value-toggle"));
    const raw = screen.getByTestId("header-value-raw");
    await user.clear(raw);
    await user.type(raw, "Token ${{vault:new}");
    await waitFor(() =>
      expect(screen.getByTestId("value")).toHaveTextContent("Token ${vault:new}"),
    );

    await user.click(screen.getByTestId("header-value-toggle"));

    expect(screen.getByTestId("header-value-prefix")).toHaveValue("Token ");
    // A held reference renders as the picker's chip, which shows the key name.
    expect(screen.getByTestId("header-value-secret")).toHaveTextContent("new");
  });

  it("does not revert a raw edit when the guided fields are typed into next", async () => {
    // The failure this actually caused: one keystroke in the prefix box after
    // the round trip re-emitted the stale pair, throwing the raw edit away.
    const user = userEvent.setup();
    renderWithProviders(<Host initial="Bearer ${vault:old}" />);

    await user.click(screen.getByTestId("header-value-toggle"));
    const raw = screen.getByTestId("header-value-raw");
    await user.clear(raw);
    await user.type(raw, "Token ${{vault:new}");
    await user.click(screen.getByTestId("header-value-toggle"));

    await user.type(screen.getByTestId("header-value-prefix"), "!");

    await waitFor(() =>
      expect(screen.getByTestId("value")).toHaveTextContent("Token !${vault:new}"),
    );
  });

  it("re-splits from empty parts when a mounted-raw value becomes splittable", async () => {
    // A field that mounted raw has no parts at all, so the first guided
    // keystroke used to replace the whole template with a single character.
    const user = userEvent.setup();
    renderWithProviders(<Host initial="${vault:a} and ${vault:b}" />);

    const raw = screen.getByTestId("header-value-raw");
    await user.clear(raw);
    await user.type(raw, "Bearer ${{vault:fixed}");

    await user.click(screen.getByTestId("header-value-toggle"));

    expect(screen.getByTestId("header-value-prefix")).toHaveValue("Bearer ");
    expect(screen.getByTestId("header-value-secret")).toHaveTextContent("fixed");
  });

  it("shows the composed template as a preview", () => {
    renderWithProviders(<Host initial="Bearer ${vault:k}" />);
    expect(screen.getByTestId("header-value-preview")).toHaveTextContent(
      "Bearer ${vault:k}",
    );
  });
});
