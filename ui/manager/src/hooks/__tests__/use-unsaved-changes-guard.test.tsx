import { renderHook } from "@testing-library/react";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
describe("useUnsavedChangesGuard", () => {
  let addSpy: ReturnType<typeof vi.spyOn>;
  let removeSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    addSpy = vi.spyOn(window, "addEventListener");
    removeSpy = vi.spyOn(window, "removeEventListener");
  });

  afterEach(() => {
    addSpy.mockRestore();
    removeSpy.mockRestore();
  });

  it("adds beforeunload listener when dirty", () => {
    renderHook(() => useUnsavedChangesGuard(true));
    expect(addSpy).toHaveBeenCalledWith(
      "beforeunload",
      expect.any(Function)
    );
  });

  it("does not add listener when not dirty", () => {
    renderHook(() => useUnsavedChangesGuard(false));
    const calls = addSpy.mock.calls.filter(
      (call) => call[0] === "beforeunload"
    );
    expect(calls).toHaveLength(0);
  });

  it("removes listener on cleanup when dirty", () => {
    const { unmount } = renderHook(() => useUnsavedChangesGuard(true));
    unmount();
    expect(removeSpy).toHaveBeenCalledWith(
      "beforeunload",
      expect.any(Function)
    );
  });

  it("removes listener when switching from dirty to clean", () => {
    const { rerender } = renderHook(
      ({ isDirty }) => useUnsavedChangesGuard(isDirty),
      { initialProps: { isDirty: true } }
    );
    rerender({ isDirty: false });
    expect(removeSpy).toHaveBeenCalledWith(
      "beforeunload",
      expect.any(Function)
    );
  });

  it("beforeunload handler calls preventDefault", () => {
    renderHook(() => useUnsavedChangesGuard(true));
    const handler = addSpy.mock.calls.find(
      (call) => call[0] === "beforeunload"
    )?.[1] as EventListener;
    expect(handler).toBeDefined();

    const event = new Event("beforeunload") as BeforeUnloadEvent;
    const preventSpy = vi.spyOn(event, "preventDefault");
    handler(event);
    expect(preventSpy).toHaveBeenCalled();
  });
});
