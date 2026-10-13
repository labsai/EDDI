import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { UnsavedChangesDialog } from "@/components/ui/unsaved-changes-dialog";

/**
 * The unsaved-changes prompt is opened on top of other dialogs. Both listen for
 * Escape on `window`, and `stopPropagation` does not stop a second listener on
 * the same target — so one Escape used to close both, losing the edit the prompt
 * existed to protect.
 */
describe("stacked dialogs and Escape", () => {
  it("closes only the top (unsaved-changes) dialog", async () => {
    const onClose = vi.fn();
    const onCancel = vi.fn();
    render(
      <>
        <AccessibleDialog open onClose={onClose} title="Editor">
          <p>editing</p>
        </AccessibleDialog>
        <UnsavedChangesDialog open onConfirm={() => {}} onCancel={onCancel} />
      </>,
    );

    await userEvent.setup().keyboard("{Escape}");

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("an AccessibleDialog on its own still closes on Escape", async () => {
    const onClose = vi.fn();
    render(
      <AccessibleDialog open onClose={onClose} title="Editor">
        <p>editing</p>
      </AccessibleDialog>,
    );
    await userEvent.setup().keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("uses the labels it is given", () => {
    render(
      <UnsavedChangesDialog
        open
        onConfirm={() => {}}
        onCancel={() => {}}
        cancelLabel="Stay"
        confirmLabel="Discard"
      />,
    );
    expect(screen.getByRole("button", { name: "Stay" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Discard" })).toBeInTheDocument();
  });
});
