import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretKeyPicker } from "../secret-key-picker";

/** A picker that owns its value, so typing into it behaves as it does in an editor. */
function ControlledPicker({ initial = "" }: { initial?: string }) {
  const [value, setValue] = useState(initial);
  return <SecretKeyPicker value={value} onChange={setValue} connections />;
}

/**
 * `${connection:name}` in the picker.
 *
 * Before this, the scheme was unknown to the reference grammar, so the picker
 * treated a connection reference as a raw secret: masked it behind dots and
 * offered to store it in the vault — which would have vaulted the literal
 * string `${connection:jira}` under a key name of the user's choosing. Kept in
 * its own file so the vault mode's suite stays a statement about vault mode.
 */

describe("SecretKeyPicker with a connection reference", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    onChange.mockReset();
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
      http.get("*/secretstore/secrets/default", () => HttpResponse.json([])),
    );
  });

  it("renders it as a Connection chip — unmasked, and not as a vault key", () => {
    renderWithProviders(<SecretKeyPicker value="${connection:jira}" onChange={onChange} />);

    const chip = screen.getByTestId("secret-key-picker-connection-chip");
    expect(chip).toHaveTextContent("Connection");
    expect(chip).toHaveTextContent("jira");
    // No password box, and no "not found in the vault" — there is no key to look up.
    expect(screen.queryByTestId("secret-key-picker-input")).not.toBeInTheDocument();
    expect(
      screen.queryByTitle("This key was not found in the vault"),
    ).not.toBeInTheDocument();
  });

  it("stays an editable input while a reference is typed, and becomes a chip at the closing brace", async () => {
    // The chip used to appear the moment `${connection:` was typed — a prefix
    // check — which unmounted the input before the name or the brace could be
    // typed. Only a finished, valid reference is a chip.
    const user = userEvent.setup();
    renderWithProviders(<ControlledPicker />);

    const reference = "${connection:jira}";
    for (let i = 0; i < reference.length - 1; i += 1) {
      const char = reference[i]!;
      // user-event reads `{` as the start of a key descriptor; `{{` is a literal brace.
      await user.type(screen.getByTestId("secret-key-picker-input"), char === "{" ? "{{" : char);

      const input = screen.getByTestId("secret-key-picker-input");
      expect(input).toHaveValue(reference.slice(0, i + 1));
      expect(screen.queryByTestId("secret-key-picker-connection-chip")).not.toBeInTheDocument();
    }
    // Unmasked while in progress: a connection reference is not a secret.
    const input = screen.getByTestId("secret-key-picker-input");
    expect(input).toHaveAttribute("type", "text");

    await user.type(input, "}");

    expect(screen.getByTestId("secret-key-picker-connection-chip")).toHaveTextContent("jira");
    expect(screen.queryByTestId("secret-key-picker-input")).not.toBeInTheDocument();
  });

  it("does not render an unfinished connection reference as a vault key", () => {
    renderWithProviders(<SecretKeyPicker value="${connection:jir" onChange={onChange} connections />);

    expect(screen.getByTestId("secret-key-picker-input")).toHaveValue("${connection:jir");
    expect(screen.queryByTestId("secret-key-picker-connection-chip")).not.toBeInTheDocument();
    expect(screen.queryByTitle("This key was not found in the vault")).not.toBeInTheDocument();
  });

  it("keeps a reference with a name the backend refuses as editable text, not a chip", () => {
    renderWithProviders(
      <SecretKeyPicker value="${connection:bad name}" onChange={onChange} connections />,
    );

    const input = screen.getByTestId("secret-key-picker-input");
    expect(input).toHaveValue("${connection:bad name}");
    expect(input).toHaveAttribute("type", "text");
    expect(screen.queryByTestId("secret-key-picker-connection-chip")).not.toBeInTheDocument();
  });

  it("clears the reference like any other chip", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="${connection:jira}" onChange={onChange} />);

    await user.click(screen.getByTestId("secret-key-picker-clear"));

    expect(onChange).toHaveBeenCalledWith("");
  });

  it("does not offer the connection list unless the field asks for it", () => {
    // Only three places resolve a connection reference; everywhere else the
    // offer would be an offer to fail at build time.
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} />);
    expect(screen.queryByTestId("secret-key-picker-connection-btn")).not.toBeInTheDocument();
  });

  it("lists the deployment's connections and inserts the chosen reference", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} connections />);

    await user.click(screen.getByTestId("secret-key-picker-connection-btn"));
    await user.click(
      await screen.findByTestId("secret-key-picker-connection-btn-option-amplitude"),
    );

    expect(onChange).toHaveBeenCalledWith("${connection:amplitude}");
  });

  it("does not offer a connection whose name no reference can carry", async () => {
    // A stored document predating the backend's name grammar. Inserting
    // `${connection:bad name}` would put a reference in the field that resolves
    // for nobody, so only the valid one is offered.
    const configs: Record<string, { name: string }> = {
      connok: { name: "jira" },
      connbad: { name: "bad name" },
    };
    server.use(
      http.get("*/connectionstore/connections/descriptors", () =>
        HttpResponse.json(
          Object.entries(configs).map(([id, config], i) => ({
            resource: `eddi://ai.labs.connection/connectionstore/connections/${id}?version=1`,
            name: config.name,
            description: "",
            createdOn: 1,
            lastModifiedOn: 10 - i,
          })),
        ),
      ),
      http.get("*/connectionstore/connections/:id", ({ params }) =>
        HttpResponse.json({
          ...configs[params.id as string],
          authType: "STATIC",
          binding: "SERVICE",
          baseUrlAllowlist: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} connections />);

    await user.click(screen.getByTestId("secret-key-picker-connection-btn"));

    expect(
      await screen.findByTestId("secret-key-picker-connection-btn-option-jira"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("secret-key-picker-connection-btn-option-bad name"),
    ).not.toBeInTheDocument();
  });

  it("explains a 403 as a role limit, not a failure, and leaves typing open", async () => {
    // An `eddi-editor` may list connections; a plain viewer may not. The
    // reference can still be typed by hand, and the hint says so.
    server.use(
      http.get(
        "*/connectionstore/connections/descriptors",
        () => new HttpResponse(null, { status: 403 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} connections />);

    await user.click(screen.getByTestId("secret-key-picker-connection-btn"));

    expect(
      await screen.findByTestId("secret-key-picker-connection-btn-forbidden"),
    ).toHaveTextContent("${connection:name}");
    expect(screen.queryByTestId("error-state")).not.toBeInTheDocument();
  });

  it("explains a 404 as a backend without the feature", async () => {
    server.use(
      http.get(
        "*/connectionstore/connections/descriptors",
        () => new HttpResponse(null, { status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} connections />);

    await user.click(screen.getByTestId("secret-key-picker-connection-btn"));

    expect(
      await screen.findByTestId("secret-key-picker-connection-btn-unavailable"),
    ).toBeInTheDocument();
  });

  it("offers a Retry that refetches the list after a failure", async () => {
    // A 400 is not retried by the query itself, so the failed state is shown at
    // once; the Retry action is the only way back, and it must really refetch.
    let listed = 0;
    server.use(
      http.get("*/connectionstore/connections/descriptors", () => {
        listed += 1;
        // Fail once; returning nothing afterwards falls through to the default
        // handler, which serves the real mock connections, "jira" among them.
        if (listed === 1) return new HttpResponse(null, { status: 400 });
        return undefined;
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} connections />);

    await user.click(screen.getByTestId("secret-key-picker-connection-btn"));
    expect(
      await screen.findByTestId("secret-key-picker-connection-btn-failed"),
    ).toBeInTheDocument();
    expect(listed).toBe(1);

    await user.click(screen.getByTestId("secret-key-picker-connection-btn-retry"));

    // A second request reaching the mock is not the list on screen: wait for the
    // failed state to go and the refetched option to render.
    await waitFor(() =>
      expect(
        screen.queryByTestId("secret-key-picker-connection-btn-failed"),
      ).not.toBeInTheDocument(),
    );
    expect(
      await screen.findByTestId("secret-key-picker-connection-btn-option-jira"),
    ).toBeInTheDocument();
    expect(listed).toBe(2);
  });

  it("does not fetch the list until the popup opens", async () => {
    let listed = 0;
    server.use(
      http.get("*/connectionstore/connections/descriptors", () => {
        listed += 1;
        return HttpResponse.json([]);
      }),
    );
    renderWithProviders(<SecretKeyPicker value="" onChange={onChange} connections />);

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(listed).toBe(0);
  });

  it("refuses a connection reference in a reference-only field", async () => {
    // clientSecret and passwordRef take `${vault:…}` and nothing else; the
    // backend refuses a connection there and so must the field.
    renderWithProviders(
      <SecretKeyPicker value="${connection:jira}" onChange={onChange} referenceOnly connections />,
    );

    await waitFor(() =>
      expect(screen.getByTestId("secret-key-picker-literal-warning")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("secret-key-picker-connection-chip")).not.toBeInTheDocument();
    expect(screen.queryByTestId("secret-key-picker-connection-btn")).not.toBeInTheDocument();
  });
});
