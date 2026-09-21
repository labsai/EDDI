import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { StrictMode } from "react";
import { render, screen } from "@testing-library/react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

/**
 * Where focus goes when a dialog closes. The initial-focus frame is held (never
 * run) so these tests see only what the open/close path itself does.
 */
describe("AccessibleDialog return focus", () => {
  let trigger: HTMLButtonElement;

  beforeEach(() => {
    vi.stubGlobal("requestAnimationFrame", () => 0);
    vi.stubGlobal("cancelAnimationFrame", () => {});
    trigger = document.createElement("button");
    trigger.textContent = "Open";
    document.body.appendChild(trigger);
    trigger.focus();
  });

  afterEach(() => {
    trigger.remove();
    vi.unstubAllGlobals();
  });

  function dialog(open: boolean) {
    return (
      <AccessibleDialog open={open} onClose={() => {}} title="T">
        <input data-testid="field" autoFocus />
      </AccessibleDialog>
    );
  }

  it("returns focus to the trigger, not to an autoFocus field inside the dialog", () => {
    // The field takes focus during commit, before any effect runs, so recording
    // "what had focus" in an effect recorded the dialog's own field — and
    // focusing that once it had unmounted left focus on <body>.
    const { rerender } = render(dialog(true));
    expect(screen.getByTestId("field")).toHaveFocus();

    rerender(dialog(false));

    expect(trigger).toHaveFocus();
  });

  it("returns focus when the dialog is closed by unmounting it", () => {
    // `{target && <ShareDialog open … />}` never renders `open={false}`, so a
    // close path that only ran on that prop change never ran for it.
    const { unmount } = render(dialog(true));

    unmount();

    expect(trigger).toHaveFocus();
  });

  it("leaves focus in the dialog through StrictMode's mount-time effect cleanup", () => {
    // StrictMode runs the effect, its cleanup and the effect again on mount,
    // with the dialog still rendered. A cleanup that restored unconditionally
    // would pull focus back to the trigger while the dialog was open.
    render(<StrictMode>{dialog(true)}</StrictMode>);

    expect(screen.getByTestId("field")).toHaveFocus();
  });

  it("does not take focus back from something the user focused outside", () => {
    const { rerender } = render(dialog(true));
    const elsewhere = document.createElement("button");
    document.body.appendChild(elsewhere);
    elsewhere.focus();

    rerender(dialog(false));

    expect(elsewhere).toHaveFocus();
    elsewhere.remove();
  });
});
