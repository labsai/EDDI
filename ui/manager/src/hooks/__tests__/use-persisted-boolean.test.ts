import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach, vi } from "vitest";
import { usePersistedBoolean } from "../use-persisted-boolean";

describe("usePersistedBoolean", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("returns default value when nothing is persisted", () => {
    const { result } = renderHook(() =>
      usePersistedBoolean("test-key", true),
    );
    expect(result.current[0]).toBe(true);
  });

  it("reads persisted 'true' from localStorage", () => {
    localStorage.setItem("test-key", "true");
    const { result } = renderHook(() =>
      usePersistedBoolean("test-key", false),
    );
    expect(result.current[0]).toBe(true);
  });

  it("reads persisted 'false' from localStorage", () => {
    localStorage.setItem("test-key", "false");
    const { result } = renderHook(() =>
      usePersistedBoolean("test-key", true),
    );
    expect(result.current[0]).toBe(false);
  });

  it("persists value changes to localStorage", () => {
    const { result } = renderHook(() =>
      usePersistedBoolean("test-key", false),
    );
    act(() => result.current[1](true));
    expect(result.current[0]).toBe(true);
    expect(localStorage.getItem("test-key")).toBe("true");
  });

  it("falls back to default when localStorage throws", () => {
    const spy = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("Quota exceeded");
    });
    const { result } = renderHook(() =>
      usePersistedBoolean("test-key", true),
    );
    expect(result.current[0]).toBe(true);
    spy.mockRestore();
  });

  it("survives localStorage.setItem throwing", () => {
    const spy = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("Quota exceeded");
    });
    const { result } = renderHook(() =>
      usePersistedBoolean("test-key", false),
    );
    // Should not throw
    act(() => result.current[1](true));
    expect(result.current[0]).toBe(true);
    spy.mockRestore();
  });

  it("rehydrates when key changes", () => {
    // Pre-populate different values for two keys
    localStorage.setItem("key-a", "true");
    localStorage.setItem("key-b", "false");

    const { result, rerender } = renderHook(
      ({ key }) => usePersistedBoolean(key, true),
      { initialProps: { key: "key-a" } },
    );
    expect(result.current[0]).toBe(true);

    // Switch key — should rehydrate from key-b
    rerender({ key: "key-b" });
    expect(result.current[0]).toBe(false);
  });

  it("reads default when switching to a key with no stored value", () => {
    localStorage.setItem("key-a", "false");

    const { result, rerender } = renderHook(
      ({ key }) => usePersistedBoolean(key, true),
      { initialProps: { key: "key-a" } },
    );
    expect(result.current[0]).toBe(false);

    // Switch to a key that has no stored value — should use defaultValue
    rerender({ key: "key-c" });
    expect(result.current[0]).toBe(true);
  });

  it("does not overwrite new key with stale value from old key", () => {
    localStorage.setItem("key-a", "true");
    localStorage.setItem("key-b", "false");

    const { rerender } = renderHook(
      ({ key }) => usePersistedBoolean(key, true),
      { initialProps: { key: "key-a" } },
    );

    // Switch to key-b
    rerender({ key: "key-b" });

    // key-b should still be "false", not overwritten with "true" from key-a
    expect(localStorage.getItem("key-b")).toBe("false");
  });
});
