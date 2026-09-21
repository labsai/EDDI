import { describe, it, expect, beforeEach } from "vitest";
import { useOperatorDrawerStore } from "@/hooks/use-operator-drawer";

describe("useOperatorDrawerStore", () => {
  beforeEach(() => {
    useOperatorDrawerStore.setState({ isOpen: false });
  });

  it("starts closed", () => {
    expect(useOperatorDrawerStore.getState().isOpen).toBe(false);
  });

  it("open() opens it", () => {
    useOperatorDrawerStore.getState().open();
    expect(useOperatorDrawerStore.getState().isOpen).toBe(true);
  });

  it("close() closes it", () => {
    useOperatorDrawerStore.getState().open();
    useOperatorDrawerStore.getState().close();
    expect(useOperatorDrawerStore.getState().isOpen).toBe(false);
  });

  it("toggle() flips it either way", () => {
    useOperatorDrawerStore.getState().toggle();
    expect(useOperatorDrawerStore.getState().isOpen).toBe(true);
    useOperatorDrawerStore.getState().toggle();
    expect(useOperatorDrawerStore.getState().isOpen).toBe(false);
  });
});
