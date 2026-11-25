import { afterEach, describe, expect, it, vi } from "vitest";
import {
  applyTheme,
  isThemeMode,
  nextTheme,
  readStoredTheme,
  resolveTheme,
  storeTheme,
  subscribeSystemTheme,
} from "./theme";

afterEach(() => {
  vi.restoreAllMocks();
  document.documentElement.className = "";
  document.documentElement.removeAttribute("data-theme");
});

describe("theme helpers", () => {
  it("marks <html> with a class for the Manager and data-theme for the widget", () => {
    applyTheme("dark", "class");
    expect(document.documentElement.classList.contains("dark")).toBe(true);
    applyTheme("light", "class");
    expect(document.documentElement.className).toBe("light");
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);

    applyTheme("dark", "data-theme");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("resolves system against the operating system and keeps an explicit mode", () => {
    expect(resolveTheme("system", "dark")).toBe("dark");
    expect(resolveTheme("system", "light")).toBe("light");
    expect(resolveTheme("light", "dark")).toBe("light");
    // The test setup's matchMedia prefers dark.
    expect(resolveTheme("system")).toBe("dark");
  });

  it("remembers a mode and ignores anything stored that is not one", () => {
    storeTheme("k", "light");
    expect(readStoredTheme("k")).toBe("light");
    localStorage.setItem("k", "purple");
    expect(readStoredTheme("k")).toBeNull();
    expect(readStoredTheme("absent")).toBeNull();
  });

  it("treats storage that throws as nothing remembered", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => storeTheme("k", "dark")).not.toThrow();
    expect(readStoredTheme("k")).toBeNull();
  });

  it("cycles dark → light → system → dark", () => {
    expect(nextTheme("dark")).toBe("light");
    expect(nextTheme("light")).toBe("system");
    expect(nextTheme("system")).toBe("dark");
  });

  it("knows the three modes", () => {
    expect(["dark", "light", "system"].every(isThemeMode)).toBe(true);
    expect(isThemeMode("auto")).toBe(false);
    expect(isThemeMode(null)).toBe(false);
  });

  it("reports an operating-system flip and unsubscribes", () => {
    const listeners: Array<() => void> = [];
    const remove = vi.fn();
    let dark = false;
    vi.spyOn(window, "matchMedia").mockImplementation(
      () =>
        ({
          get matches() {
            return dark;
          },
          addEventListener: (_: string, l: () => void) => listeners.push(l),
          removeEventListener: remove,
        }) as unknown as MediaQueryList,
    );
    const seen: string[] = [];
    const unsubscribe = subscribeSystemTheme((theme) => seen.push(theme));
    dark = true;
    listeners[0]?.();
    dark = false;
    listeners[0]?.();
    expect(seen).toEqual(["dark", "light"]);
    unsubscribe();
    expect(remove).toHaveBeenCalledTimes(1);
  });
});
