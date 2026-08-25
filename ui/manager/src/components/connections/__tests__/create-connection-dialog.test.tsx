import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CreateConnectionDialog } from "@/components/connections/create-connection-dialog";

/**
 * The wizard, and the three ways it used to lose or mis-handle what was typed.
 */

/**
 * Render, then wait for the dialog to stop moving focus.
 *
 * `AccessibleDialog` focuses its first focusable in a `requestAnimationFrame`
 * on mount. Typing before that fires races it: the callback lands mid-word,
 * focus jumps to the close button, and the rest of the characters go nowhere —
 * which surfaces later as an inexplicably invalid field.
 */
async function renderDialog(onCreated = vi.fn()) {
  const onOpenChange = vi.fn();
  const result = renderWithProviders(
    <CreateConnectionDialog open onOpenChange={onOpenChange} onCreated={onCreated} />,
  );
  await waitFor(() => expect(screen.getByLabelText("Close")).toHaveFocus());
  return { ...result, onCreated, onOpenChange };
}

/** Fill step 1 with a valid name and advance. */
async function toCredentials(user: ReturnType<typeof userEvent.setup>) {
  const name = screen.getByTestId("create-connection-name");
  await user.type(name, "notion");
  expect(name).toHaveValue("notion");
  await user.click(screen.getByTestId("create-connection-next"));
  await screen.findByTestId("create-connection-header-name");
}

describe("CreateConnectionDialog — the auth type chooser", () => {
  it("is a real radio group, not four unrelated radios", async () => {
    // `role="radio"` with no owning radiogroup is invalid ARIA: the options are
    // announced with no group name and no "1 of 4", on the one decision the
    // wizard itself calls unchangeable afterwards.
    await renderDialog();

    const group = screen.getByRole("radiogroup");
    expect(group).toBeInTheDocument();
    expect(within(group).getAllByRole("radio")).toHaveLength(4);
  });

  it("moves the selection with the arrow keys", async () => {
    const user = userEvent.setup();
    await renderDialog();

    const first = screen.getByTestId("auth-type-choice-STATIC");
    expect(first).toHaveAttribute("aria-checked", "true");

    // Click rather than `.focus()`: the dialog's focus trap runs its own
    // rAF-scheduled focus on mount and would otherwise take it back.
    await user.click(first);
    await user.keyboard("{ArrowDown}");

    await waitFor(() =>
      expect(screen.getByTestId("auth-type-choice-BASIC")).toHaveAttribute(
        "aria-checked",
        "true",
      ),
    );
  });

  it("keeps the group to a single tab stop", async () => {
    await renderDialog();
    // Roving tabIndex: the selected option is reachable, the rest are not.
    expect(screen.getByTestId("auth-type-choice-STATIC")).toHaveAttribute(
      "tabindex",
      "0",
    );
    expect(screen.getByTestId("auth-type-choice-BASIC")).toHaveAttribute(
      "tabindex",
      "-1",
    );
  });
});

describe("CreateConnectionDialog — uncommitted input", () => {
  it("creates with an origin typed but never committed to a chip", async () => {
    // The dead end this removes: validation reported "add at least one origin"
    // directly underneath an input that visibly contained one.
    const user = userEvent.setup();
    let sent: Record<string, unknown> | null = null;
    server.use(
      http.post("*/connectionstore/connections", async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>;
        return new HttpResponse(null, {
          status: 201,
          headers: { Location: "/connectionstore/connections/new-1?version=1" },
        });
      }),
    );
    const { onCreated } = await renderDialog();

    await toCredentials(user);
    await user.type(
      screen.getByTestId("create-connection-header-name"),
      "X-Api-Key",
    );
    await user.type(
      screen.getByTestId("create-connection-header-value-secret-input"),
      "${{vault:notion-key}",
    );
    await user.click(screen.getByTestId("create-connection-next"));

    await user.type(
      screen.getByTestId("create-connection-origins-input"),
      "https://api.notion.com",
    );
    await user.click(screen.getByTestId("create-connection-submit"));

    await waitFor(() => expect(sent).not.toBeNull());
    expect(sent!.baseUrlAllowlist).toEqual(["https://api.notion.com"]);
    expect(onCreated).toHaveBeenCalledWith("new-1", 1);
  });
});

describe("CreateConnectionDialog — the created response", () => {
  it("does not navigate when the backend sent no Location header", async () => {
    // A proxy that strips the header would otherwise parse "" into an empty id
    // and bounce the user to the list right after telling them it worked.
    const user = userEvent.setup();
    server.use(
      http.post(
        "*/connectionstore/connections",
        () => new HttpResponse(null, { status: 201 }),
      ),
    );
    const { onCreated, onOpenChange } = await renderDialog();

    await toCredentials(user);
    await user.type(
      screen.getByTestId("create-connection-header-name"),
      "X-Api-Key",
    );
    await user.type(
      screen.getByTestId("create-connection-header-value-secret-input"),
      "${{vault:notion-key}",
    );
    await user.click(screen.getByTestId("create-connection-next"));
    await user.type(
      screen.getByTestId("create-connection-origins-input"),
      "https://api.notion.com",
    );
    await user.click(screen.getByTestId("create-connection-submit"));

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false));
    expect(onCreated).not.toHaveBeenCalled();
  });
});

describe("CreateConnectionDialog — step gating", () => {
  it("will not advance past a step whose own field is invalid", async () => {
    const user = userEvent.setup();
    await renderDialog();

    // Empty name: Next must not move, and the reason must appear.
    await user.click(screen.getByTestId("create-connection-next"));

    expect(screen.getByTestId("create-connection-name")).toBeInTheDocument();
    expect(screen.queryByTestId("create-connection-submit")).not.toBeInTheDocument();
  });

  it("names the fault rather than just refusing", async () => {
    const user = userEvent.setup();
    await renderDialog();

    // A name the backend's own pattern rejects — ${connection:…} would stop
    // resolving silently, which is why the format is enforced this early.
    await user.type(screen.getByTestId("create-connection-name"), "not a name!");
    await user.click(screen.getByTestId("create-connection-next"));

    expect(screen.getByTestId("create-connection-name")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });

  it("does not block a step on a field belonging to a later one", async () => {
    // The classic way a wizard becomes unusable: the allowlist is required, but
    // it lives two steps ahead and must not hold up the name.
    const user = userEvent.setup();
    await renderDialog();

    await user.type(screen.getByTestId("create-connection-name"), "notion");
    await user.click(screen.getByTestId("create-connection-next"));

    expect(screen.queryByTestId("create-connection-name")).not.toBeInTheDocument();
  });

  it("clears the fault as soon as the field is corrected", async () => {
    const user = userEvent.setup();
    await renderDialog();

    await user.click(screen.getByTestId("create-connection-next"));
    await user.type(screen.getByTestId("create-connection-name"), "notion");
    await user.click(screen.getByTestId("create-connection-next"));

    expect(screen.queryByTestId("create-connection-name")).not.toBeInTheDocument();
  });
});

describe("CreateConnectionDialog — discarding", () => {
  it("asks before throwing away a part-filled wizard", async () => {
    const user = userEvent.setup();
    const { onOpenChange } = await renderDialog();

    await user.type(screen.getByTestId("create-connection-name"), "notion");
    await user.keyboard("{Escape}");

    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    expect(onOpenChange).not.toHaveBeenCalled();

    await user.click(screen.getByTestId("unsaved-confirm"));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("closes an untouched wizard without ceremony", async () => {
    const user = userEvent.setup();
    const { onOpenChange } = await renderDialog();

    await user.keyboard("{Escape}");

    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
