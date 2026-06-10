import { describe, it, expect, vi, beforeEach, beforeAll, afterAll } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { CommandPalette } from "@/components/shared/command-palette";
import { useCommandPalette } from "@/hooks/use-command-palette";

// cmdk uses ResizeObserver and scrollIntoView internally
beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  Element.prototype.scrollIntoView = vi.fn();
});

afterAll(() => {
  // @ts-expect-error restore
  delete global.ResizeObserver;
});

describe("CommandPalette", () => {
  beforeEach(() => {
    useCommandPalette.setState({
      isOpen: false,
      recentPages: [],
    });
  });

  it("renders nothing when closed", () => {
    const { container } = renderWithProviders(<CommandPalette />);
    expect(container.innerHTML).toBe("");
  });

  it("renders dialog when opened via store", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    expect(
      screen.getByPlaceholderText("Search pages, agents, actions…")
    ).toBeInTheDocument();
  });

  it("shows Navigate section with page links", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    // Page items
    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getAllByText("Agents").length).toBeGreaterThan(0);
    expect(screen.getByText("Workflows")).toBeInTheDocument();
    expect(screen.getByText("Resources")).toBeInTheDocument();
  });

  it("shows Quick Actions section", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    expect(screen.getByText("Create New Agent")).toBeInTheDocument();
    expect(screen.getByText("Open Chat")).toBeInTheDocument();
  });

  it("shows keyboard shortcuts in footer", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    expect(screen.getByText("navigate")).toBeInTheDocument();
    expect(screen.getByText("select")).toBeInTheDocument();
    expect(screen.getByText("close")).toBeInTheDocument();
    expect(screen.getByText("Ctrl+K")).toBeInTheDocument();
  });

  it("shows recent pages when they exist", () => {
    useCommandPalette.setState({
      isOpen: true,
      recentPages: [
        { path: "/manage/agents", label: "Agents" },
        { path: "/manage/chat", label: "Chat" },
      ],
    });
    renderWithProviders(<CommandPalette />);
    // "Recent" heading
    expect(screen.getByText("Recent")).toBeInTheDocument();
  });

  it("closes when backdrop is clicked", async () => {
    useCommandPalette.setState({ isOpen: true });
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    // Click the backdrop
    const backdrop = document.querySelector("[aria-hidden='true']") as HTMLElement;
    expect(backdrop).not.toBeNull();
    await user.click(backdrop!);

    expect(useCommandPalette.getState().isOpen).toBe(false);
  });

  it("opens with Ctrl+K keyboard shortcut", async () => {
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard("{Control>}k{/Control}");
    expect(useCommandPalette.getState().isOpen).toBe(true);
  });

  it("closes with Escape key", async () => {
    useCommandPalette.setState({ isOpen: true });
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard("{Escape}");
    expect(useCommandPalette.getState().isOpen).toBe(false);
  });

  it("shows Esc kbd elements", () => {
    useCommandPalette.setState({ isOpen: true });
    renderWithProviders(<CommandPalette />);
    expect(screen.getAllByText("Esc").length).toBeGreaterThanOrEqual(1);
  });
});
