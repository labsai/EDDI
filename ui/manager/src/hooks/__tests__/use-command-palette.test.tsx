import { beforeEach, describe, expect, it } from "vitest";
import { act } from "@testing-library/react";
import { useCommandPalette } from "@/hooks/use-command-palette";

describe("useCommandPalette", () => {
  beforeEach(() => {
    localStorage.clear();
    act(() => {
      useCommandPalette.setState({ isOpen: false, recentPages: [] });
    });
  });

  it("starts closed", () => {
    expect(useCommandPalette.getState().isOpen).toBe(false);
  });

  it("open sets isOpen to true", () => {
    act(() => {
      useCommandPalette.getState().open();
    });
    expect(useCommandPalette.getState().isOpen).toBe(true);
  });

  it("close sets isOpen to false", () => {
    act(() => {
      useCommandPalette.getState().open();
      useCommandPalette.getState().close();
    });
    expect(useCommandPalette.getState().isOpen).toBe(false);
  });

  it("toggle flips isOpen", () => {
    act(() => {
      useCommandPalette.getState().toggle();
    });
    expect(useCommandPalette.getState().isOpen).toBe(true);

    act(() => {
      useCommandPalette.getState().toggle();
    });
    expect(useCommandPalette.getState().isOpen).toBe(false);
  });

  it("addRecentPage adds to front of list", () => {
    act(() => {
      useCommandPalette.getState().addRecentPage("/agents", "Agents");
    });
    const pages = useCommandPalette.getState().recentPages;
    expect(pages).toHaveLength(1);
    expect(pages[0]).toEqual({ path: "/agents", label: "Agents" });
  });

  it("addRecentPage deduplicates and limits to 5", () => {
    act(() => {
      useCommandPalette.getState().addRecentPage("/agents", "Agents");
      useCommandPalette.getState().addRecentPage("/workflows", "Workflows");
      useCommandPalette.getState().addRecentPage("/chat", "Chat");
      useCommandPalette.getState().addRecentPage("/logs", "Logs");
      useCommandPalette.getState().addRecentPage("/secrets", "Secrets");
      useCommandPalette.getState().addRecentPage("/audit", "Audit");
    });
    const pages = useCommandPalette.getState().recentPages;
    expect(pages).toHaveLength(5);
    expect(pages[0]!.path).toBe("/audit");
  });

  it("addRecentPage moves existing entry to front", () => {
    act(() => {
      useCommandPalette.getState().addRecentPage("/agents", "Agents");
      useCommandPalette.getState().addRecentPage("/workflows", "Workflows");
      useCommandPalette.getState().addRecentPage("/agents", "Agents Updated");
    });
    const pages = useCommandPalette.getState().recentPages;
    expect(pages).toHaveLength(2);
    expect(pages[0]).toEqual({ path: "/agents", label: "Agents Updated" });
  });

  it("persists recent pages to localStorage", () => {
    act(() => {
      useCommandPalette.getState().addRecentPage("/agents", "Agents");
    });
    const stored = localStorage.getItem("eddi-recent-pages");
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored!);
    expect(parsed).toHaveLength(1);
    expect(parsed[0].path).toBe("/agents");
  });
});
