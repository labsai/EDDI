import { describe, it, expect, vi } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ResizeHandle } from "@/components/ui/resize-handle";

/**
 * The handle, and the two things a pointer-only version got wrong.
 *
 * It is a `role="separator"` the user moves, so it is a widget: without a
 * tabIndex and key handling it cannot be operated without a mouse at all. And
 * a horizontal drag reports a screen delta, which means the opposite of what
 * the caller wants the moment the document is RTL — this app ships Arabic.
 */
function drag(handle: HTMLElement, from: number, to: number) {
  // Dispatched as MouseEvents with the pointer type names: jsdom's own
  // PointerEvent drops clientX/clientY from the init dict, so a
  // `fireEvent.pointerDown(el, { clientX })` arrives with clientX === null and
  // every delta computes to zero. React reads the coordinates off the native
  // event either way. Capture is stubbed for the same reason — jsdom has no
  // pointer capture.
  handle.setPointerCapture = vi.fn();
  handle.releasePointerCapture = vi.fn();
  handle.hasPointerCapture = vi.fn(() => true);
  const at = (type: string, x: number) =>
    fireEvent(
      handle,
      new MouseEvent(type, { clientX: x, clientY: x, bubbles: true }),
    );
  at("pointerdown", from);
  at("pointermove", to);
  at("pointerup", to);
}

describe("ResizeHandle", () => {
  it("reports the drag distance to its caller", () => {
    const onResize = vi.fn();
    renderWithProviders(
      <ResizeHandle direction="horizontal" onResize={onResize} label="Pipeline" />,
    );

    drag(screen.getByTestId("resize-handle"), 100, 140);

    expect(onResize).toHaveBeenCalledWith(40);
  });

  it("is reachable and operable from the keyboard", () => {
    // The whole point: a drag target with no key handling is mouse-only.
    const onResize = vi.fn();
    renderWithProviders(
      <ResizeHandle direction="horizontal" onResize={onResize} label="Pipeline" />,
    );

    const handle = screen.getByTestId("resize-handle");
    expect(handle).toHaveAttribute("tabindex", "0");

    handle.focus();
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(onResize).toHaveBeenCalledWith(16);

    fireEvent.keyDown(handle, { key: "ArrowLeft" });
    expect(onResize).toHaveBeenCalledWith(-16);
  });

  it("moves further with Shift held, for crossing a panel", () => {
    const onResize = vi.fn();
    renderWithProviders(
      <ResizeHandle direction="horizontal" onResize={onResize} label="Pipeline" />,
    );

    fireEvent.keyDown(screen.getByTestId("resize-handle"), {
      key: "ArrowRight",
      shiftKey: true,
    });

    expect(onResize).toHaveBeenCalledWith(64);
  });

  it("uses the vertical arrows when the axis is vertical", () => {
    const onResize = vi.fn();
    renderWithProviders(
      <ResizeHandle direction="vertical" onResize={onResize} label="Chat" />,
    );

    const handle = screen.getByTestId("resize-handle");
    fireEvent.keyDown(handle, { key: "ArrowDown" });
    expect(onResize).toHaveBeenCalledWith(16);

    // The other axis' arrows are not this handle's to consume.
    onResize.mockClear();
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(onResize).not.toHaveBeenCalled();
  });

  it("inverts a horizontal drag under RTL, where right means smaller", () => {
    // Arabic ships. Without this the caller's `width + delta` grows the panel
    // when the user drags it towards its own edge.
    const onResize = vi.fn();
    renderWithProviders(
      <div dir="rtl">
        <ResizeHandle direction="horizontal" onResize={onResize} label="Pipeline" />
      </div>,
    );

    drag(screen.getByTestId("resize-handle"), 100, 140);

    expect(onResize).toHaveBeenCalledWith(-40);
  });

  it("inverts the arrows under RTL too", () => {
    const onResize = vi.fn();
    renderWithProviders(
      <div dir="rtl">
        <ResizeHandle direction="horizontal" onResize={onResize} label="Pipeline" />
      </div>,
    );

    fireEvent.keyDown(screen.getByTestId("resize-handle"), { key: "ArrowRight" });

    expect(onResize).toHaveBeenCalledWith(-16);
  });

  it("leaves a vertical handle alone in RTL, which only mirrors the horizontal axis", () => {
    const onResize = vi.fn();
    renderWithProviders(
      <div dir="rtl">
        <ResizeHandle direction="vertical" onResize={onResize} label="Chat" />
      </div>,
    );

    fireEvent.keyDown(screen.getByTestId("resize-handle"), { key: "ArrowDown" });

    expect(onResize).toHaveBeenCalledWith(16);
  });

  it("reports its position, not just its role", () => {
    // A separator that announces no value tells a screen reader nothing about
    // what the arrows just did.
    renderWithProviders(
      <ResizeHandle
        direction="horizontal"
        onResize={vi.fn()}
        label="Pipeline"
        value={256}
        min={180}
        max={400}
      />,
    );

    const handle = screen.getByTestId("resize-handle");
    expect(handle).toHaveAttribute("role", "separator");
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
    expect(handle).toHaveAttribute("aria-valuenow", "256");
    expect(handle).toHaveAttribute("aria-valuemin", "180");
    expect(handle).toHaveAttribute("aria-valuemax", "400");
    expect(handle).toHaveAccessibleName("Resize Pipeline");
  });

  it("ignores a move that never started with a press", () => {
    const onResize = vi.fn();
    renderWithProviders(
      <ResizeHandle direction="horizontal" onResize={onResize} label="Pipeline" />,
    );

    fireEvent.pointerMove(screen.getByTestId("resize-handle"), {
      pointerId: 1,
      clientX: 999,
    });

    expect(onResize).not.toHaveBeenCalled();
  });
});
