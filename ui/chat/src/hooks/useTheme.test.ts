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

  it("follows system changes after setTheme(\"system\") when storage is blocked", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    let systemDark = false;
    const listeners: Array<() => void> = [];
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      get matches() {
        return systemDark;
      },
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: (_type: string, listener: () => void) => listeners.push(listener),
      removeEventListener: () => {},
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
    try {
      const { result } = renderHook(() => useTheme("light"));
      act(() => result.current.setTheme("system"));
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");

      // The OS switches to dark: the page chose "system", so it must follow —
      // even though the choice could not be stored.
      systemDark = true;
      act(() => listeners.forEach((listener) => listener()));
      expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

      // And a later explicit choice stops it following again.
      act(() => result.current.setTheme("light"));
      systemDark = false;
      act(() => listeners.forEach((listener) => listener()));
      systemDark = true;
      act(() => listeners.forEach((listener) => listener()));
      expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    } finally {
      window.matchMedia = original;
    }
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
