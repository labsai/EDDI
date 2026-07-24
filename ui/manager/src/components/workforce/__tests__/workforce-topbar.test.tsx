import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { WorkforceTopbar } from "@/components/workforce/workforce-topbar";

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }));
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return { ...actual, useNavigate: () => mockNavigate };
});

describe("WorkforceTopbar", () => {
  it("renders the header element", () => {
    renderWithProviders(<WorkforceTopbar />);
    expect(screen.getByRole("banner")).toBeInTheDocument();
  });

  it("renders a title when provided", () => {
    renderWithProviders(<WorkforceTopbar title="My Board" />);
    expect(screen.getByText("My Board")).toBeInTheDocument();
  });

  it("renders a back button when backTo is set", () => {
    renderWithProviders(<WorkforceTopbar backTo="/workforce" />);
    const btn = screen.getByRole("button", { name: /back/i });
    expect(btn).toBeInTheDocument();
  });

  it("navigates on back button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(<WorkforceTopbar backTo="/workforce" />);
    await user.click(screen.getByRole("button", { name: /back/i }));
    expect(mockNavigate).toHaveBeenCalledWith("/workforce");
  });

  it("renders a menu button when onMenuClick is set and no backTo", async () => {
    const handleMenu = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<WorkforceTopbar onMenuClick={handleMenu} />);
    const btn = screen.getByRole("button", { name: /open menu/i });
    await user.click(btn);
    expect(handleMenu).toHaveBeenCalled();
  });

  it("renders right content when provided", () => {
    renderWithProviders(
      <WorkforceTopbar rightContent={<span data-testid="right">Action</span>} />,
    );
    expect(screen.getByTestId("right")).toBeInTheDocument();
  });
});
