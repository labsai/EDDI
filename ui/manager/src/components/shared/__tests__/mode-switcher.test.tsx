import { describe, expect, it, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ModeSwitcher } from "@/components/shared/mode-switcher";

describe("ModeSwitcher", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("renders both options as links", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />, { initialRoute: "/manage/agents" });
    expect(screen.getByTestId("mode-option-manager")).toHaveAttribute("href", "/manage");
    expect(screen.getByTestId("mode-option-workforce")).toHaveAttribute("href", "/workforce");
  });

  it("shows both 'Manager' and 'Workforce' labels when expanded", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />, { initialRoute: "/manage/agents" });
    expect(screen.getByText("Manager")).toBeInTheDocument();
    expect(screen.getByText("Workforce")).toBeInTheDocument();
  });

  it("marks Manager as the current page when on a /manage route", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />, { initialRoute: "/manage/agents" });
    expect(screen.getByTestId("mode-option-manager")).toHaveAttribute("aria-current", "page");
    expect(screen.getByTestId("mode-option-workforce")).not.toHaveAttribute("aria-current");
  });

  it("is not an ARIA tablist: arrow keys must not swap the whole app", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />, { initialRoute: "/manage/agents" });

    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: /switch workspace/i })).toBeInTheDocument();

    screen.getByTestId("mode-option-manager").focus();
    await user.keyboard("{ArrowRight}");
    // Still on Manager — nothing navigated.
    expect(screen.getByTestId("mode-option-manager")).toHaveAttribute("aria-current", "page");
  });

  it("renders an icon-only link to the other mode when collapsed", () => {
    renderWithProviders(<ModeSwitcher collapsed={true} />, { initialRoute: "/manage/agents" });
    const trigger = screen.getByRole("link", { name: /switch workspace/i });
    expect(trigger).toHaveAttribute("href", "/workforce");
    // No text labels in collapsed mode
    expect(screen.queryByText("Manager")).not.toBeInTheDocument();
    expect(screen.queryByText("Workforce")).not.toBeInTheDocument();
  });

  it("collapsed link points at the Manager when already in Workforce", () => {
    renderWithProviders(<ModeSwitcher collapsed={true} />, { initialRoute: "/workforce/board-1" });
    expect(screen.getByRole("link", { name: /switch workspace/i })).toHaveAttribute("href", "/manage");
  });

  it("does NOT rewrite the saved landing preference when switching mode", async () => {
    // The preference is the visitor's explicit answer on the welcome chooser; a
    // hop to Workforce for one task must not overwrite it.
    localStorage.setItem("eddi-landing-preference", "manage");
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />, { initialRoute: "/manage/agents" });

    await user.click(screen.getByTestId("mode-option-workforce"));

    expect(localStorage.getItem("eddi-landing-preference")).toBe("manage");
  });

  it("clicking the already-active mode stays where it is", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />, { initialRoute: "/manage/agents" });

    const manager = screen.getByTestId("mode-option-manager");
    await user.click(manager);
    // The click is swallowed, so the active link keeps its current marker.
    expect(manager).toHaveAttribute("aria-current", "page");
  });
});
