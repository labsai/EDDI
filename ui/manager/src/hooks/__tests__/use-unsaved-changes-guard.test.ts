import { describe, it, expect, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";

describe("useUnsavedChangesGuard", () => {
  it("does not add beforeunload listener when isDirty is false", () => {
    const addSpy = vi.spyOn(window, "addEventListener");
    renderHook(() => useUnsavedChangesGuard(false));
    expect(
      addSpy.mock.calls.filter(([name]) => name === "beforeunload"),
    ).toHaveLength(0);
    addSpy.mockRestore();
  });

  it("adds beforeunload listener when isDirty is true", () => {
    const addSpy = vi.spyOn(window, "addEventListener");
    renderHook(() => useUnsavedChangesGuard(true));
    expect(
      addSpy.mock.calls.filter(([name]) => name === "beforeunload"),
    ).toHaveLength(1);
    addSpy.mockRestore();
  });

  it("removes beforeunload listener on cleanup", () => {
    const removeSpy = vi.spyOn(window, "removeEventListener");
    const { unmount } = renderHook(() => useUnsavedChangesGuard(true));
    unmount();
    expect(
      removeSpy.mock.calls.filter(([name]) => name === "beforeunload"),
    ).toHaveLength(1);
    removeSpy.mockRestore();
  });

  it("removes listener when isDirty changes from true to false", () => {
    const removeSpy = vi.spyOn(window, "removeEventListener");
    const { rerender } = renderHook(
      ({ dirty }) => useUnsavedChangesGuard(dirty),
      { initialProps: { dirty: true } },
    );
    rerender({ dirty: false });
    expect(
      removeSpy.mock.calls.filter(([name]) => name === "beforeunload"),
    ).toHaveLength(1);
    removeSpy.mockRestore();
  });

  it("beforeunload handler calls preventDefault and sets returnValue", () => {
    renderHook(() => useUnsavedChangesGuard(true));
    const event = new Event("beforeunload") as BeforeUnloadEvent;
    const preventDefaultSpy = vi.spyOn(event, "preventDefault");
    window.dispatchEvent(event);
    expect(preventDefaultSpy).toHaveBeenCalled();
  });
});
