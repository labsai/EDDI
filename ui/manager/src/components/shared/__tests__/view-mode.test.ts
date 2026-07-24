import { beforeEach, describe, expect, it, vi } from "vitest";
import { getStoredViewMode, setStoredViewMode } from "../view-mode";

describe("view-mode utilities", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  describe("getStoredViewMode", () => {
    it("returns 'card' as default when no value is stored", () => {
      expect(getStoredViewMode("bots")).toBe("card");
    });

    it("returns 'card' when stored value is 'card'", () => {
      localStorage.setItem("eddi-view-mode-bots", "card");
      expect(getStoredViewMode("bots")).toBe("card");
    });

    it("returns 'list' when stored value is 'list'", () => {
      localStorage.setItem("eddi-view-mode-packages", "list");
      expect(getStoredViewMode("packages")).toBe("list");
    });

    it("returns default 'card' when stored value is invalid", () => {
      localStorage.setItem("eddi-view-mode-bots", "invalid-mode");
      expect(getStoredViewMode("bots")).toBe("card");

      localStorage.setItem("eddi-view-mode-bots", "grid");
      expect(getStoredViewMode("bots")).toBe("card");

      localStorage.setItem("eddi-view-mode-bots", "");
      expect(getStoredViewMode("bots")).toBe("card");
    });

    it("handles localStorage throwing an error gracefully and defaults to 'card'", () => {
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("SecurityError: localStorage blocked");
      });

      expect(getStoredViewMode("bots")).toBe("card");
    });
  });

  describe("setStoredViewMode", () => {
    it("persists 'card' mode to localStorage with page prefix", () => {
      setStoredViewMode("bots", "card");
      expect(localStorage.getItem("eddi-view-mode-bots")).toBe("card");
    });

    it("persists 'list' mode to localStorage with page prefix", () => {
      setStoredViewMode("conversations", "list");
      expect(localStorage.getItem("eddi-view-mode-conversations")).toBe("list");
    });

    it("handles localStorage throwing an error gracefully when persisting", () => {
      vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
        throw new Error("QuotaExceededError");
      });

      expect(() => setStoredViewMode("bots", "list")).not.toThrow();
    });
  });

  describe("integration between get and set", () => {
    it("retrieves stored value for the exact page namespace", () => {
      setStoredViewMode("pageA", "list");
      setStoredViewMode("pageB", "card");

      expect(getStoredViewMode("pageA")).toBe("list");
      expect(getStoredViewMode("pageB")).toBe("card");
      expect(getStoredViewMode("pageC")).toBe("card");
    });
  });
});
