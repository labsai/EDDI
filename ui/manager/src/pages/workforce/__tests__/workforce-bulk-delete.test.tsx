import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";

import { toast } from "sonner";

import { WorkforceDashboard } from "../workforce-dashboard";

/**
 * Bulk delete used to fire on the click, with nothing in between.
 *
 * Deleting ONE task force has always gone through an AlertDialog — WorkforceCard
 * renders it — so the more destructive action was the one with the weaker guard.
 * These tests are written against the network rather than against the dialog:
 * what matters is not that a dialog appeared, it is that nothing was deleted
 * until someone said so. Asserting on the dialog alone would still pass if the
 * confirm button were wired to nothing at all.
 */

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

/** Records every DELETE the page actually sends. */
function watchDeletes() {
  const deleted: string[] = [];
  server.use(
    http.delete("*/groupstore/groups/:id", ({ params }) => {
      deleted.push(String(params.id));
      return new HttpResponse(null, { status: 204 });
    }),
  );
  return deleted;
}

/**
 * Put three task forces on the page.
 *
 * The default fixture cannot: the generic wildcard descriptors handler
 * (handlers.ts:1340) is registered ~700 lines ahead of the eight-group fixture at
 * handlers.ts:2034 and exempts only `channelstore`, so `groupstore` gets a one-row
 * echo stub. Every other test in this file therefore runs a one-item "bulk"
 * delete, and without this override the plural title and plural toast are
 * rendered by no test at all.
 */
function serveThreeTaskForces() {
  const groups = ["alpha", "beta", "gamma"].map((id, i) => ({
    resource: `eddi://ai.labs.group/groupstore/groups/${id}?version=1`,
    name: `Task Force ${id}`,
    description: `the ${id} one`,
    createdOn: Date.now() - 86400000,
    lastModifiedOn: Date.now() - i * 1000,
  }));
  server.use(
    http.get("*/groupstore/groups/descriptors", () => HttpResponse.json(groups)),
  );
  return groups;
}

/** Enter bulk mode and select every task force on the page. */
async function selectAllTaskForces(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Select" }));
  const cards = (await screen.findAllByRole("button", { name: "Select" })).filter(
    (b) => b.getAttribute("aria-pressed") === "false",
  );
  for (const card of cards) await user.click(card);
  return cards.length;
}

/** Enter bulk mode and select the first task force offered. */
async function selectFirstTaskForce(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Select" }));

  const checkboxes = await screen.findAllByRole("button", { name: "Select" });
  // The toolbar's own toggle now reads "Cancel", so every remaining "Select"
  // is a card. Take the first.
  const card = checkboxes.find((b) => b.getAttribute("aria-pressed") === "false");
  expect(card, "no selectable task force was rendered").toBeTruthy();
  await user.click(card!);
}

describe("WorkforceDashboard bulk delete", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("deletes nothing until the deletion is confirmed", async () => {
    const deleted = watchDeletes();
    const user = userEvent.setup();
    renderWithProviders(<WorkforceDashboard />);

    await selectFirstTaskForce(user);
    await user.click(screen.getByTestId("bulk-delete-btn"));

    // The dialog is up...
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    // ...and this is the part that used to be false.
    expect(deleted).toEqual([]);
  });

  it("still deletes nothing when the dialog is dismissed", async () => {
    const deleted = watchDeletes();
    const user = userEvent.setup();
    renderWithProviders(<WorkforceDashboard />);

    await selectFirstTaskForce(user);
    await user.click(screen.getByTestId("bulk-delete-btn"));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(deleted).toEqual([]);
  });

  it("deletes the selected task force once confirmed", async () => {
    const deleted = watchDeletes();
    const user = userEvent.setup();
    renderWithProviders(<WorkforceDashboard />);

    await selectFirstTaskForce(user);
    await user.click(screen.getByTestId("bulk-delete-btn"));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(deleted).toHaveLength(1));
  });

  it("reads as English for a single selection", async () => {
    // `/1/` would have passed here while the heading said "Dissolve 1 task
    // forces?" — the singular is the common case, so it gets the exact string.
    const user = userEvent.setup();
    renderWithProviders(<WorkforceDashboard />);

    await selectFirstTaskForce(user);
    await user.click(screen.getByTestId("bulk-delete-btn"));

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByRole("heading")).toHaveTextContent(
      "Dissolve this task force?",
    );
    expect(within(dialog).getByText(/cannot be undone/i)).toBeInTheDocument();
  });

  /**
   * The dialog disables its confirm button on `deleteGroup.isPending`, and the
   * first attempt at a re-entrancy guard read the same value. Both come from one
   * render: the render in which the button is clickable is exactly the one whose
   * closure captured `isPending === false`, so guarding on it caught nothing the
   * disabled attribute had not already caught. Two clicks in a single tick
   * deleted the same task force twice. The guard is a ref now.
   */
  it("deletes once even if the confirm is clicked twice in a tick", async () => {
    const deleted = watchDeletes();
    const user = userEvent.setup();
    renderWithProviders(<WorkforceDashboard />);

    await selectFirstTaskForce(user);
    await user.click(screen.getByTestId("bulk-delete-btn"));

    const dialog = await screen.findByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "Delete" });

    // Not user.click twice — that awaits between them and the button is disabled
    // by the second. Two synchronous clicks are what a real double-click delivers
    // before React has re-rendered.
    fireEvent.click(confirm);
    fireEvent.click(confirm);

    await waitFor(() => expect(deleted.length).toBeGreaterThan(0));
    expect(deleted).toEqual([deleted[0]]);
  });

  /**
   * The title was pluralised and the toast one line below it was not, so the same
   * run that asked "Dissolve this task force?" reported "Deleted 1 task forces".
   */
  it("reports a single deletion in the singular", async () => {
    watchDeletes();
    const user = userEvent.setup();
    renderWithProviders(<WorkforceDashboard />);

    await selectFirstTaskForce(user);
    await user.click(screen.getByTestId("bulk-delete-btn"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(toast.success).toHaveBeenCalled());
    expect(toast.success).toHaveBeenCalledWith("Deleted 1 task force");
  });

  describe("with more than one selected", () => {
    it("asks in the plural and deletes each one", async () => {
      serveThreeTaskForces();
      const deleted = watchDeletes();
      const user = userEvent.setup();
      renderWithProviders(<WorkforceDashboard />);

      const selected = await selectAllTaskForces(user);
      expect(selected).toBe(3);
      await user.click(screen.getByTestId("bulk-delete-btn"));

      const dialog = await screen.findByRole("dialog");
      expect(within(dialog).getByRole("heading")).toHaveTextContent(
        "Dissolve 3 task forces?",
      );

      await user.click(within(dialog).getByRole("button", { name: "Delete" }));

      await waitFor(() => expect(deleted).toHaveLength(3));
      expect([...deleted].sort()).toEqual(["alpha", "beta", "gamma"]);
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Deleted 3 task forces"));
    });

    it("reports how many failed rather than claiming success", async () => {
      serveThreeTaskForces();
      server.use(
        http.delete("*/groupstore/groups/beta", () => new HttpResponse(null, { status: 500 })),
        http.delete("*/groupstore/groups/:id", () => new HttpResponse(null, { status: 204 })),
      );
      const user = userEvent.setup();
      renderWithProviders(<WorkforceDashboard />);

      await selectAllTaskForces(user);
      await user.click(screen.getByTestId("bulk-delete-btn"));
      const dialog = await screen.findByRole("dialog");
      await user.click(within(dialog).getByRole("button", { name: "Delete" }));

      await waitFor(() => expect(toast.error).toHaveBeenCalled());
      expect(toast.error).toHaveBeenCalledWith("2 deleted, 1 failed");
      expect(toast.success).not.toHaveBeenCalled();
    });
  });
});
