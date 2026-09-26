import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { userEvent, renderWithProviders } from "@/test/test-utils";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { AgentPicker } from "@/components/shared/agent-picker";

/**
 * How an AccessibleDialog is dismissed.
 *
 * - The backdrop's click handler sat UNDER the centring layer, which covers the
 *   whole viewport, so a click on the dimmed area never reached it.
 * - Escape pressed to close a popup inside the dialog (AgentPicker) also closed
 *   the dialog, discarding the form (Trigger, Edit Grant).
 */
describe("AccessibleDialog dismissal", () => {
  it("closes on a click on the dimmed area", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(
      <AccessibleDialog open onClose={onClose} title="T" testId="dlg">
        <input data-testid="field" />
      </AccessibleDialog>,
    );

    await user.click(screen.getByTestId("dlg-backdrop"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("does not close when a drag starts in a field and is released on the backdrop", () => {
    // Selecting text by dragging past the box's edge: mousedown on the input,
    // mouseup over the dimmed area — the browser sends `click` to the common
    // ancestor, which is the backdrop layer.
    const onClose = vi.fn();
    render(
      <AccessibleDialog open onClose={onClose} title="T" testId="dlg">
        <input data-testid="field" />
      </AccessibleDialog>,
    );
    fireEvent.mouseDown(screen.getByTestId("field"));
    fireEvent.click(screen.getByTestId("dlg-backdrop"));
    expect(onClose).not.toHaveBeenCalled();

    // A genuine press-and-release on the backdrop still closes.
    fireEvent.mouseDown(screen.getByTestId("dlg-backdrop"));
    fireEvent.click(screen.getByTestId("dlg-backdrop"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("does not close on a click inside the dialog box", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(
      <AccessibleDialog open onClose={onClose} title="T" testId="dlg">
        <input data-testid="field" />
      </AccessibleDialog>,
    );

    await user.click(screen.getByTestId("field"));
    await user.click(screen.getByRole("heading", { name: "T" }));
    expect(onClose).not.toHaveBeenCalled();
  });

  it("closes on Escape", () => {
    const onClose = vi.fn();
    render(
      <AccessibleDialog open onClose={onClose} title="T">
        <input data-testid="field" />
      </AccessibleDialog>,
    );
    fireEvent.keyDown(screen.getByTestId("field"), { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("stays open when a control inside already handled Escape", () => {
    const onClose = vi.fn();
    render(
      <AccessibleDialog open onClose={onClose} title="T">
        <input
          data-testid="field"
          onKeyDown={(e) => {
            if (e.key === "Escape") e.preventDefault();
          }}
        />
      </AccessibleDialog>,
    );
    fireEvent.keyDown(screen.getByTestId("field"), { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
  });

  it("Escape in an open AgentPicker closes the picker, not the dialog", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <AccessibleDialog open onClose={onClose} title="Edit trigger">
        <AgentPicker value="" onChange={() => {}} />
      </AccessibleDialog>,
    );

    const input = screen.getByPlaceholderText("Select Agent");
    await user.click(input);
    const toggle = screen.getByRole("button", { name: "Close agent list" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    await user.keyboard("{Escape}");
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show agents" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );

    // With the picker closed, the next Escape is the dialog's.
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
