import { afterEach, describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useVisualViewportHeight, VIEWPORT_HEIGHT_VAR } from "@/hooks/use-visual-viewport-height";

/** T1.11 — the composer stays above an on-screen keyboard (iOS shrinks only the visual viewport). */

class FakeVisualViewport extends EventTarget {
  height = 800;
  scale = 1;
}

const original = Object.getOwnPropertyDescriptor(window, "visualViewport");

function install(): FakeVisualViewport {
  const viewport = new FakeVisualViewport();
  Object.defineProperty(window, "visualViewport", { configurable: true, value: viewport });
  Object.defineProperty(window, "innerHeight", { configurable: true, value: 800 });
  return viewport;
}

afterEach(() => {
  if (original) Object.defineProperty(window, "visualViewport", original);
  else delete (window as { visualViewport?: unknown }).visualViewport;
  document.documentElement.style.removeProperty(VIEWPORT_HEIGHT_VAR);
});

const value = () => document.documentElement.style.getPropertyValue(VIEWPORT_HEIGHT_VAR);

describe("useVisualViewportHeight", () => {
  it("follows the visual viewport while a keyboard shrinks it, and lets go after", () => {
    const viewport = install();
    const { unmount } = renderHook(() => useVisualViewportHeight());
    expect(value()).toBe("");

    act(() => {
      viewport.height = 452.4;
      viewport.dispatchEvent(new Event("resize"));
    });
    expect(value()).toBe("452px");

    act(() => {
      viewport.height = 800;
      viewport.dispatchEvent(new Event("resize"));
    });
    expect(value()).toBe("");

    act(() => {
      viewport.height = 500;
      viewport.dispatchEvent(new Event("resize"));
    });
    unmount();
    expect(value()).toBe("");
  });

  it("ignores a pinch-zoom", () => {
    const viewport = install();
    renderHook(() => useVisualViewportHeight());
    act(() => {
      viewport.scale = 2;
      viewport.height = 400;
      viewport.dispatchEvent(new Event("resize"));
    });
    expect(value()).toBe("");
  });
});
