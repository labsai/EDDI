import { describe, expect, it, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ModeSwitcher } from "@/components/shared/mode-switcher";

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }));
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

  it("renders the segmented pill with both options visible", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    expect(screen.getByTestId("mode-option-manager")).toBeInTheDocument();
    expect(screen.getByTestId("mode-option-workforce")).toBeInTheDocument();
  });

  it("shows both 'Manager' and 'Workforce' labels when expanded", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    expect(screen.getByText("Manager")).toBeInTheDocument();
    expect(screen.getByText("Workforce")).toBeInTheDocument();
  });

  it("marks Manager as active tab when on /manage route", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    const managerTab = screen.getByTestId("mode-option-manager");
    const workforceTab = screen.getByTestId("mode-option-workforce");
    expect(managerTab).toHaveAttribute("aria-selected", "true");
    expect(workforceTab).toHaveAttribute("aria-selected", "false");
  });

  it("renders icon-only toggle when collapsed", () => {
    renderWithProviders(<ModeSwitcher collapsed={true} />);
    // Collapsed shows only a single toggle button (opposite mode icon)
    const trigger = screen.getByRole("button", { name: /switch workspace/i });
    expect(trigger).toBeInTheDocument();
    // No text labels in collapsed mode
    expect(screen.queryByText("Manager")).not.toBeInTheDocument();
    expect(screen.queryByText("Workforce")).not.toBeInTheDocument();
  });

  it("navigates to /workforce and persists preference when clicking Workforce", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByTestId("mode-option-workforce"));

    expect(mockNavigate).toHaveBeenCalledWith("/workforce");
    expect(localStorage.getItem("eddi-landing-preference")).toBe("workforce");
  });

  it("does not navigate when clicking the already-active mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    // Click Manager (already active since pathname is /manage/agents)
    await user.click(screen.getByTestId("mode-option-manager"));

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("collapsed toggle navigates to opposite mode", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={true} />);

    // When on /manage, clicking should navigate to /workforce
    await user.click(screen.getByRole("button", { name: /switch workspace/i }));

    expect(mockNavigate).toHaveBeenCalledWith("/workforce");
    expect(localStorage.getItem("eddi-landing-preference")).toBe("workforce");
  });

  it("has tablist role for accessibility", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    expect(screen.getByRole("tablist")).toBeInTheDocument();
  });
});
