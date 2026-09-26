import { describe, it, expect, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useTheme } from "./useTheme";

describe("useTheme", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    document.documentElement.removeAttribute("data-theme");
    try {
      window.localStorage.clear();
    } catch {
      // ignore
    }
  });

  it("still applies and switches the theme when storage is blocked", () => {
    // A sandboxed iframe or a third-party storage block makes every access
    // throw. That exception used to escape the effect and blank the widget.
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    const { result } = renderHook(() => useTheme("light"));
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");

    act(() => result.current.setTheme("dark"));
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("uses the remembered theme over the initial one", () => {
    window.localStorage.setItem("eddi-chat-theme", "light");
    renderHook(() => useTheme("dark"));
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("ignores a stored value that is not a theme", () => {
    window.localStorage.setItem("eddi-chat-theme", "neon");
    renderHook(() => useTheme("dark"));
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });
});
