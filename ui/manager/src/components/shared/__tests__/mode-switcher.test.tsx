import { describe, expect, it, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ModeSwitcher } from "@/components/shared/mode-switcher";

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ pathname: "/manage/agents" }),
  };
});

describe("ModeSwitcher", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    localStorage.clear();
  });

  it("renders the trigger button", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    const trigger = screen.getByRole("button", { name: /switch workspace/i });
    expect(trigger).toBeInTheDocument();
  });

  it("shows 'Manager' label when expanded and on /manage route", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    expect(screen.getByText("Manager")).toBeInTheDocument();
  });

  it("hides label text when collapsed", () => {
    renderWithProviders(<ModeSwitcher collapsed={true} />);
    // When collapsed, the text label is not rendered (icon-only)
    expect(screen.queryByText("Manager")).not.toBeInTheDocument();
    // But the trigger is still accessible via aria-label
    expect(screen.getByRole("button", { name: /switch workspace/i })).toBeInTheDocument();
  });

  it("opens dropdown on click and shows both options", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByRole("button", { name: /switch workspace/i }));
    const menu = screen.getByRole("menu");
    expect(menu).toBeInTheDocument();

    const items = within(menu).getAllByRole("menuitem");
    expect(items).toHaveLength(2);
  });

  it("closes dropdown on Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByRole("button", { name: /switch workspace/i }));
    expect(screen.getByRole("menu")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("navigates to /workforce and persists preference when selecting Workforce", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    // Open dropdown
    await user.click(screen.getByRole("button", { name: /switch workspace/i }));

    // Click on Workforce option
    const workforceOption = screen.getByTestId("mode-option-workforce");
    await user.click(workforceOption);

    // Should navigate to /workforce
    expect(mockNavigate).toHaveBeenCalledWith("/workforce");
    // Should persist preference
    expect(localStorage.getItem("eddi-landing-preference")).toBe("workforce");
    // Dropdown should close
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("does not navigate when selecting the already-active mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByRole("button", { name: /switch workspace/i }));

    // Click on Manager (already active since pathname is /manage/agents)
    const managerOption = screen.getByTestId("mode-option-manager");
    await user.click(managerOption);

    // Should NOT navigate since we're already on manager
    expect(mockNavigate).not.toHaveBeenCalled();
    // Dropdown should close
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("shows checkmark on the active mode option", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByRole("button", { name: /switch workspace/i }));

    // Manager (active) should have a checkmark icon
    const managerOption = screen.getByTestId("mode-option-manager");
    expect(managerOption.querySelector("svg.lucide-check")).not.toBeNull();

    // Workforce (inactive) should NOT have a checkmark
    const workforceOption = screen.getByTestId("mode-option-workforce");
    expect(workforceOption.querySelector("svg.lucide-check")).toBeNull();
  });

  it("has correct aria attributes on trigger", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    const trigger = screen.getByRole("button", { name: /switch workspace/i });
    expect(trigger).toHaveAttribute("aria-haspopup", "true");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });
});
