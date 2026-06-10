import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useTargetRect } from "@/hooks/use-target-rect";

describe("useTargetRect", () => {
  beforeEach(() => {
    // Reset any elements
    document.body.innerHTML = "";
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("returns null when targetSelector is empty", () => {
    const { result } = renderHook(() => useTargetRect(""));
    expect(result.current).toBeNull();
  });

  it("returns null when element is not found", () => {
    const { result } = renderHook(() => useTargetRect("#nonexistent"));
    expect(result.current).toBeNull();
  });

  it("returns rect when element is found", () => {
    const el = document.createElement("div");
    el.setAttribute("id", "test-target");
    document.body.appendChild(el);

    // Mock getBoundingClientRect
    vi.spyOn(el, "getBoundingClientRect").mockReturnValue({
      top: 100,
      left: 50,
      width: 200,
      height: 40,
      bottom: 140,
      right: 250,
      x: 50,
      y: 100,
      toJSON: () => ({}),
    });

    const { result } = renderHook(() => useTargetRect("#test-target"));

    expect(result.current).toEqual({
      top: 92, // 100 - 8 (default padding)
      left: 42, // 50 - 8
      width: 216, // 200 + 8*2
      height: 56, // 40 + 8*2
    });
  });

  it("uses custom padding", () => {
    const el = document.createElement("div");
    el.setAttribute("id", "test-padded");
    document.body.appendChild(el);

    vi.spyOn(el, "getBoundingClientRect").mockReturnValue({
      top: 100,
      left: 50,
      width: 200,
      height: 40,
      bottom: 140,
      right: 250,
      x: 50,
      y: 100,
      toJSON: () => ({}),
    });

    const { result } = renderHook(() => useTargetRect("#test-padded", 16));

    expect(result.current).toEqual({
      top: 84, // 100 - 16
      left: 34, // 50 - 16
      width: 232, // 200 + 16*2
      height: 72, // 40 + 16*2
    });
  });

  it("updates on resize events", () => {
    const el = document.createElement("div");
    el.setAttribute("id", "test-resize");
    document.body.appendChild(el);

    const mockGetBCR = vi.spyOn(el, "getBoundingClientRect").mockReturnValue({
      top: 100, left: 50, width: 200, height: 40,
      bottom: 140, right: 250, x: 50, y: 100,
      toJSON: () => ({}),
    });

    const { result } = renderHook(() => useTargetRect("#test-resize"));
    expect(result.current).not.toBeNull();

    // Simulate resize
    mockGetBCR.mockReturnValue({
      top: 200, left: 100, width: 300, height: 60,
      bottom: 260, right: 400, x: 100, y: 200,
      toJSON: () => ({}),
    });

    // Use requestAnimationFrame-based update
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });
  });

  it("cleans up event listeners on unmount", () => {
    const removeSpy = vi.spyOn(window, "removeEventListener");
    const { unmount } = renderHook(() => useTargetRect("#test"));
    unmount();

    const removedEvents = removeSpy.mock.calls.map(([name]) => name);
    expect(removedEvents).toContain("resize");
    expect(removedEvents).toContain("scroll");
    removeSpy.mockRestore();
  });
});
