import { beforeEach, describe, expect, it, vi } from "vitest";
import { clearWorkforcePins, pinsStorageKey, WORKFORCE_PINS_PREFIX } from "../workforce-pins";

describe("pinsStorageKey", () => {
  it("names the principal and the board", () => {
    expect(pinsStorageKey("b1", "alice")).toBe("workforce-pins-alice:b1");
  });

  it("keeps two principals apart even when their names contain the delimiter or dashes", () => {
    expect(pinsStorageKey("b1", "a:b")).not.toBe(pinsStorageKey("b1", "a"));
    expect(pinsStorageKey("1", "x-y")).not.toBe(pinsStorageKey("y-1", "x"));
  });

  it("falls back to one shared name only when there is no principal", () => {
    expect(pinsStorageKey("b1", null)).toBe(pinsStorageKey("b1", ""));
    expect(pinsStorageKey("b1", undefined)).toBe(pinsStorageKey("b1", "  "));
    expect(pinsStorageKey("b1", null)).not.toBe(pinsStorageKey("b1", "alice"));
  });
});

describe("clearWorkforcePins", () => {
  beforeEach(() => localStorage.clear());

  it("removes every pin key, old and new, and nothing else", () => {
    localStorage.setItem(`${WORKFORCE_PINS_PREFIX}b1`, "[]"); // legacy shape
    localStorage.setItem(pinsStorageKey("b1", "alice"), "[]");
    localStorage.setItem(pinsStorageKey("b2", "bob"), "[]");
    localStorage.setItem("eddi-theme", "dark");

    clearWorkforcePins();

    expect(localStorage.length).toBe(1);
    expect(localStorage.getItem("eddi-theme")).toBe("dark");
  });

  it("does not throw when storage is blocked", () => {
    const blocked = {
      get length(): number {
        throw new Error("denied");
      },
    } as unknown as Storage;
    expect(() => clearWorkforcePins(blocked)).not.toThrow();
    const spy = vi.spyOn(Storage.prototype, "key").mockImplementation(() => {
      throw new Error("denied");
    });
    localStorage.setItem("workforce-pins-x", "[]");
    expect(() => clearWorkforcePins()).not.toThrow();
    spy.mockRestore();
  });
});
