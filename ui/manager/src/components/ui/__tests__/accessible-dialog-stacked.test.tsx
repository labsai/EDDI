import { describe, it, expect, vi } from "vitest";
import { fireEvent, render } from "@testing-library/react";
import { AccessibleDialog } from "@eddi/app-kit/components/ui/accessible-dialog";

describe("AccessibleDialog — stacked dialogs", () => {
  it("closes only the top-most dialog on Escape", () => {
    const closeBottom = vi.fn();
    const closeTop = vi.fn();
    render(
      <>
        <AccessibleDialog open onClose={closeBottom} title="Bottom">
          <p>form</p>
        </AccessibleDialog>
        <AccessibleDialog open onClose={closeTop} title="Top">
          <p>create</p>
        </AccessibleDialog>
      </>,
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(closeTop).toHaveBeenCalledTimes(1);
    expect(closeBottom).not.toHaveBeenCalled();
  });

  it("hands Escape back to the one below once the top one is gone", () => {
    const closeBottom = vi.fn();
    const { rerender } = render(
      <>
        <AccessibleDialog open onClose={closeBottom} title="Bottom">
          <p>form</p>
        </AccessibleDialog>
        <AccessibleDialog open onClose={vi.fn()} title="Top">
          <p>create</p>
        </AccessibleDialog>
      </>,
    );
    rerender(
      <AccessibleDialog open onClose={closeBottom} title="Bottom">
        <p>form</p>
      </AccessibleDialog>,
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(closeBottom).toHaveBeenCalledTimes(1);
  });

  it("keeps the stack order when the lower dialog gets a new onClose", () => {
    const firstBottom = vi.fn();
    const newBottom = vi.fn();
    const closeTop = vi.fn();
    const ui = (bottom: () => void) => (
      <>
        <AccessibleDialog open onClose={bottom} title="Bottom">
          <p>form</p>
        </AccessibleDialog>
        <AccessibleDialog open onClose={closeTop} title="Top">
          <p>create</p>
        </AccessibleDialog>
      </>
    );
    const { rerender } = render(ui(firstBottom));
    // The parent re-renders the lower dialog with a fresh callback.
    rerender(ui(newBottom));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(closeTop).toHaveBeenCalledTimes(1);
    expect(firstBottom).not.toHaveBeenCalled();
    expect(newBottom).not.toHaveBeenCalled();
  });

  it("calls the latest onClose it was given", () => {
    const first = vi.fn();
    const latest = vi.fn();
    const { rerender } = render(
      <AccessibleDialog open onClose={first} title="Only">
        <p>x</p>
      </AccessibleDialog>,
    );
    rerender(
      <AccessibleDialog open onClose={latest} title="Only">
        <p>x</p>
      </AccessibleDialog>,
    );
    fireEvent.keyDown(window, { key: "Escape" });
    expect(latest).toHaveBeenCalledTimes(1);
    expect(first).not.toHaveBeenCalled();
  });
});

describe("AccessibleDialog — outside clicks and the focus trap", () => {
  it("closes on a click on the area around the panel, not on one inside it", () => {
    const onClose = vi.fn();
    const { getByTestId } = render(
      <AccessibleDialog open onClose={onClose} title="T" testId="dlg">
        <input data-testid="field" />
      </AccessibleDialog>,
    );
    fireEvent.mouseDown(getByTestId("field"));
    fireEvent.click(getByTestId("field"));
    expect(onClose).not.toHaveBeenCalled();

    // A selection dragged out of the field ends on the overlay: not an outside click.
    fireEvent.mouseDown(getByTestId("field"));
    fireEvent.click(getByTestId("dlg-overlay"));
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.mouseDown(getByTestId("dlg-overlay"));
    fireEvent.click(getByTestId("dlg-overlay"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("wraps Tab over the enabled controls only", () => {
    const { getByTestId } = render(
      <AccessibleDialog open onClose={vi.fn()} title="T" testId="dlg" closeTestId="close">
        <button data-testid="cancel">Cancel</button>
        <button data-testid="save" disabled>
          Save
        </button>
      </AccessibleDialog>,
    );
    // Tab from Cancel — the last control that can take focus — wraps to Close.
    getByTestId("cancel").focus();
    fireEvent.keyDown(getByTestId("dlg"), { key: "Tab" });
    expect(getByTestId("close")).toHaveFocus();
    // Shift+Tab from Close goes to Cancel, not to the disabled Save.
    fireEvent.keyDown(getByTestId("dlg"), { key: "Tab", shiftKey: true });
    expect(getByTestId("cancel")).toHaveFocus();
  });
});
