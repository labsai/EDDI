import { describe, it, expect, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { Sidebar } from "@/components/layout/sidebar";

describe("Sidebar — collapsible sections", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("renders section toggle buttons when expanded", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );

    // Section headers should be buttons with aria-expanded
    const sectionButtons = screen.getAllByRole("button", { expanded: true });
    // At least Core, Build, Monitor, Admin = 4 section toggles
    // (sidebar-toggle + sidebar-help are also buttons, but not expanded)
    expect(sectionButtons.length).toBeGreaterThanOrEqual(4);
  });

  it("does not render section toggle buttons when sidebar collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />,
    );

    // No section labels/buttons are shown in collapsed mode
    const sectionButtons = screen.queryAllByRole("button", { expanded: true });
    // Should be 0 — only the collapse toggle and help button are present
    expect(sectionButtons.length).toBe(0);
  });

  it("shows all items by default (no sections collapsed)", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );

    // Should see items from all sections
    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByText("Resources")).toBeInTheDocument();
    expect(screen.getByText("Logs")).toBeInTheDocument();
    expect(screen.getByText("Secrets")).toBeInTheDocument();
  });

  it("hides section items when section header is clicked", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );

    // Admin section has "Secrets" — find the Admin section toggle
    // Sections are: Core (0), Build (1), Monitor (2), Admin (3)
    // Click on the Admin section header
    const adminButton = screen.getAllByRole("button", { expanded: true })
      .find(btn => btn.textContent?.toLowerCase().includes("admin"));
    expect(adminButton).toBeDefined();

    fireEvent.click(adminButton!);

    // Admin items should be hidden now
    expect(screen.queryByText("Secrets")).not.toBeInTheDocument();
    // But items from other sections should still be visible
    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByText("Logs")).toBeInTheDocument();
  });

  it("persists collapsed state in localStorage", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );

    // Click Admin section
    const adminButton = screen.getAllByRole("button", { expanded: true })
      .find(btn => btn.textContent?.toLowerCase().includes("admin"));
    fireEvent.click(adminButton!);

    // Check localStorage was updated
    const stored = localStorage.getItem("eddi-sidebar-sections");
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored!);
    expect(parsed).toContain(3); // Admin is index 3
  });

  it("restores collapsed state from localStorage", () => {
    // Pre-set collapsed state: Admin (index 3) collapsed
    localStorage.setItem("eddi-sidebar-sections", JSON.stringify([3]));

    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );

    // Admin items should be hidden on load
    expect(screen.queryByText("Secrets")).not.toBeInTheDocument();
    // Core items should still be visible
    expect(screen.getByText("Dashboard")).toBeInTheDocument();
  });

  it("shows all items in icon mode even when sections are collapsed", () => {
    // Pre-set collapsed state
    localStorage.setItem("eddi-sidebar-sections", JSON.stringify([3]));

    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />,
    );

    // In collapsed mode, items are shown as icons with aria-label
    // All items should still be accessible
    expect(screen.getByLabelText("Secrets")).toBeInTheDocument();
    expect(screen.getByLabelText("Dashboard")).toBeInTheDocument();
  });

  it("renders User Data nav item instead of separate Memories/Properties/UserConversations", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />,
    );

    expect(screen.getByText("User Data")).toBeInTheDocument();
    expect(screen.queryByText("Memories")).not.toBeInTheDocument();
    expect(screen.queryByText("User Conversations")).not.toBeInTheDocument();
  });
});
