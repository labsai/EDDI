import { describe, it, expect, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { usePinnedGroups } from "@/hooks/use-pinned-groups";

describe("usePinnedGroups", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("Initially returns empty set", () => {
    const { result } = renderHook(() => usePinnedGroups());
    expect(result.current.pinned.size).toBe(0);
  });

  it("togglePin adds a group ID", () => {
    const { result } = renderHook(() => usePinnedGroups());
    act(() => {
      result.current.togglePin("group-1");
    });
    expect(result.current.pinned.has("group-1")).toBe(true);
    expect(result.current.pinned.size).toBe(1);
  });

  it("togglePin removes an already-pinned group ID", () => {
    const { result } = renderHook(() => usePinnedGroups());
    act(() => {
      result.current.togglePin("group-1");
    });
    expect(result.current.pinned.has("group-1")).toBe(true);
    act(() => {
      result.current.togglePin("group-1");
    });
    expect(result.current.pinned.has("group-1")).toBe(false);
    expect(result.current.pinned.size).toBe(0);
  });

  it("isPinned returns true for pinned, false for unpinned", () => {
    const { result } = renderHook(() => usePinnedGroups());
    act(() => {
      result.current.togglePin("group-1");
    });
    expect(result.current.isPinned("group-1")).toBe(true);
    expect(result.current.isPinned("group-2")).toBe(false);
  });

  it("Persists to localStorage", () => {
    const { result } = renderHook(() => usePinnedGroups());
    act(() => {
      result.current.togglePin("group-1");
    });
    
    // Check if it's in local storage
    const stored = localStorage.getItem("workforce-pinned-groups");
    expect(stored).toBe(JSON.stringify(["group-1"]));

    // Check if another hook instance reads it
    const { result: result2 } = renderHook(() => usePinnedGroups());
    expect(result2.current.pinned.has("group-1")).toBe(true);
  });

  it("Handles corrupt localStorage gracefully", () => {
    localStorage.setItem("workforce-pinned-groups", "invalid json");
    const { result } = renderHook(() => usePinnedGroups());
    expect(result.current.pinned.size).toBe(0);
  });
});
