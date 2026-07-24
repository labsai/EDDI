import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { WorkforceBottomTabs } from "@/components/workforce/workforce-bottom-tabs";

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }));
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ pathname: "/workforce" }),
  };
});

describe("WorkforceBottomTabs", () => {
  it("renders the navigation", () => {
    renderWithProviders(<WorkforceBottomTabs />);
    expect(screen.getByRole("navigation", { name: /bottom/i })).toBeInTheDocument();
  });

  it("renders three tab buttons", () => {
    renderWithProviders(<WorkforceBottomTabs />);
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(3);
  });

  it("marks Home as current when on /workforce", () => {
    renderWithProviders(<WorkforceBottomTabs />);
    const homeBtn = screen.getByText("Home").closest("button");
    expect(homeBtn).toHaveAttribute("aria-current", "page");
  });

  it("navigates when clicking a tab", async () => {
    const user = userEvent.setup();
    renderWithProviders(<WorkforceBottomTabs />);
    await user.click(screen.getByText("Insights"));
    expect(mockNavigate).toHaveBeenCalledWith("/workforce/analytics");
  });
});
