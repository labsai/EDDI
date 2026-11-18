import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { AuthContext, GUEST_CONTEXT } from "@eddi/app-kit/components/auth/auth-context";
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

  /*
   * The user menu loads lazily. Its placeholder holds the avatar's 2rem only
   * where an avatar will appear: with authentication off the menu renders
   * nothing, and a placeholder there would appear and vanish on every load.
   */
  describe("user menu", () => {
    it("renders neither the menu nor a placeholder with authentication off", async () => {
      renderWithProviders(<WorkforceTopbar />);
      expect(screen.queryByTestId("user-menu-placeholder")).not.toBeInTheDocument();
      // Still nothing once anything lazy would have resolved.
      await new Promise((resolve) => setTimeout(resolve, 0));
      expect(screen.queryByTestId("user-menu-placeholder")).not.toBeInTheDocument();
      expect(screen.queryByTestId("user-menu-trigger")).not.toBeInTheDocument();
    });

    it("holds the avatar's place while the menu loads, then shows the menu, when signed in", async () => {
      renderWithProviders(
        <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles: [] }}>
          <WorkforceTopbar />
        </AuthContext.Provider>,
      );
      expect(screen.getByTestId("user-menu-placeholder")).toBeInTheDocument();
      expect(await screen.findByTestId("user-menu-trigger")).toBeInTheDocument();
      expect(screen.queryByTestId("user-menu-placeholder")).not.toBeInTheDocument();
    });
  });
});
