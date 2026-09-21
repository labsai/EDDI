import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";

/**
 * The initial focus runs in a `requestAnimationFrame`, so when it lands depends
 * on the machine. These tests hold the frame and release it by hand, which is
 * the only way to put "the user already focused a field" deterministically
 * before it — the ordering a loaded CI runner produced by accident.
 */
describe("AccessibleDialog initial focus", () => {
  let frames: FrameRequestCallback[];

  beforeEach(() => {
    frames = [];
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => frames.push(cb));
    vi.stubGlobal("cancelAnimationFrame", () => {});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function runFrames() {
    act(() => frames.forEach((cb) => cb(0)));
  }

  it("focuses the first focusable element when nothing inside has focus", () => {
    render(
      <AccessibleDialog open onClose={() => {}} title="T">
        <input data-testid="field" />
      </AccessibleDialog>
    );

    runFrames();

    expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
  });

  it("leaves focus on a field the user reached before the frame ran", () => {
    // Moving it to the Close button sent the rest of the user's typing — and
    // their Enter — to a button instead of the field they had clicked.
    render(
      <AccessibleDialog open onClose={() => {}} title="T">
        <input data-testid="field" />
      </AccessibleDialog>
    );

    screen.getByTestId("field").focus();
    runFrames();

    expect(screen.getByTestId("field")).toHaveFocus();
  });

  it("leaves an autoFocus field focused", () => {
    render(
      <AccessibleDialog open onClose={() => {}} title="T">
        <input data-testid="field" autoFocus />
      </AccessibleDialog>
    );

    runFrames();

    expect(screen.getByTestId("field")).toHaveFocus();
  });
});
