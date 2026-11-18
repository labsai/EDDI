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
});
