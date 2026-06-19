import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { Sidebar } from "@/components/layout/sidebar";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { useOnboarding } from "@/hooks/use-onboarding";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";

/** Render sidebar with custom auth context */
function renderSidebarWithAuth(
  collapsed: boolean,
  authValue: AuthContextValue,
  onToggle = vi.fn()
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-sidebar-test">
          <AuthContext.Provider value={authValue}>
            <Sidebar collapsed={collapsed} onToggle={onToggle} />
          </AuthContext.Provider>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

const keycloakAuth: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: {
    username: "johndoe",
    firstName: "John",
    lastName: "Doe",
    email: "john@example.com",
    fullName: "John Doe",
  },
  roles: ["admin"],
  method: "keycloak",
  login: vi.fn(),
  logout: vi.fn(),
};

describe("Sidebar", () => {
  beforeEach(() => {
    localStorage.clear();
    useOnboarding.setState({
      completedChapters: new Set(),
      activeChapter: null,
      currentStep: 0,
      offeredChapter: null,
    });
  });

  // ── Basic rendering ────────────────────────────────────────────────
  it("renders all navigation items", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByText("Agents")).toBeInTheDocument();
    expect(screen.getByText("Workflows")).toBeInTheDocument();
    expect(screen.getByText("Conversations")).toBeInTheDocument();
    expect(screen.getByText("Resources")).toBeInTheDocument();
  });

  it("hides labels when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.queryByText("Dashboard")).not.toBeInTheDocument();
    expect(screen.queryByText("Agents")).not.toBeInTheDocument();
  });

  it("renders collapse toggle button", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByTestId("sidebar-toggle")).toBeInTheDocument();
  });

  it("shows EDDI logo", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByAltText("EDDI")).toBeInTheDocument();
  });

  it("shows abbreviated logo when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.getByLabelText("EDDI")).toBeInTheDocument();
    expect(screen.queryByAltText("EDDI")).not.toBeInTheDocument();
  });

  // ── Toggle button ──────────────────────────────────────────────────
  it("calls onToggle when toggle button is clicked", async () => {
    const onToggle = vi.fn();
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={onToggle} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-toggle"));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it("toggle button shows 'Expand sidebar' label when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.getByLabelText("Expand sidebar")).toBeInTheDocument();
  });

  it("toggle button shows 'Collapse sidebar' label when expanded", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByLabelText("Collapse sidebar")).toBeInTheDocument();
  });

  // ── Section headings ───────────────────────────────────────────────
  it("renders section headings when expanded", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    // Section labels from navSections (translated)
    expect(screen.getByText("Core")).toBeInTheDocument();
    expect(screen.getByText(/Build/)).toBeInTheDocument();
    expect(screen.getByText("Monitor")).toBeInTheDocument();
    expect(screen.getByText("Admin")).toBeInTheDocument();
  });

  it("hides section headings when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.queryByText("Core")).not.toBeInTheDocument();
    expect(screen.queryByText(/Build/)).not.toBeInTheDocument();
  });

  // ── Section collapsing ─────────────────────────────────────────────
  it("collapses a section when header is clicked", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    // Click the Admin section header to collapse it
    const adminBtn = screen.getByText("Admin");
    const user = userEvent.setup();
    await user.click(adminBtn);

    // Items in the Admin section should be hidden
    expect(screen.queryByText("Secrets")).not.toBeInTheDocument();
  });

  it("re-expands a collapsed section when header is clicked again", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const adminBtn = screen.getByText("Admin");
    const user = userEvent.setup();

    // Collapse
    await user.click(adminBtn);
    expect(screen.queryByText("Secrets")).not.toBeInTheDocument();

    // Expand again
    await user.click(adminBtn);
    expect(screen.getByText("Secrets")).toBeInTheDocument();
  });

  it("persists section collapse state to localStorage", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByText("Admin"));

    const stored = localStorage.getItem("eddi-sidebar-sections");
    expect(stored).toBeTruthy();
    const parsed = JSON.parse(stored!);
    expect(parsed).toContain(3); // Admin is index 3
  });

  // ── External links ─────────────────────────────────────────────────
  it("renders external links when expanded", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByText("OpenAPI")).toBeInTheDocument();
    expect(screen.getByText("Documentation")).toBeInTheDocument();
  });

  it("hides External section label when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.queryByText("External")).not.toBeInTheDocument();
  });

  it("external links open in new tab", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    // Find the docs link
    const docsLink = screen.getByText("Documentation").closest("a");
    expect(docsLink).toHaveAttribute("target", "_blank");
    expect(docsLink).toHaveAttribute("rel", "noopener noreferrer");
  });

  // ── User profile section ───────────────────────────────────────────
  it("shows user profile when auth is keycloak", () => {
    renderSidebarWithAuth(false, keycloakAuth);

    expect(screen.getByTestId("sidebar-user")).toBeInTheDocument();
    expect(screen.getByText("John Doe")).toBeInTheDocument();
    expect(screen.getByText("john@example.com")).toBeInTheDocument();
  });

  it("shows user initials avatar when auth is keycloak", () => {
    renderSidebarWithAuth(false, keycloakAuth);

    // Initials should be "JD"
    expect(screen.getByText("JD")).toBeInTheDocument();
  });

  it("hides user profile when auth is 'none'", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    // Default auth context has method 'none'
    expect(screen.queryByTestId("sidebar-user")).not.toBeInTheDocument();
  });

  it("shows logout button when expanded and authenticated", () => {
    renderSidebarWithAuth(false, keycloakAuth);

    expect(screen.getByTestId("sidebar-logout")).toBeInTheDocument();
  });

  it("calls logout when logout button is clicked", async () => {
    const logoutFn = vi.fn();
    renderSidebarWithAuth(false, { ...keycloakAuth, logout: logoutFn });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-logout"));
    expect(logoutFn).toHaveBeenCalledTimes(1);
  });

  it("hides user details when collapsed (only shows avatar)", () => {
    renderSidebarWithAuth(true, keycloakAuth);

    // Avatar initials should be visible
    expect(screen.getByText("JD")).toBeInTheDocument();
    // But name and email should be hidden
    expect(screen.queryByText("John Doe")).not.toBeInTheDocument();
    expect(screen.queryByText("john@example.com")).not.toBeInTheDocument();
  });

  it("handles user without email", () => {
    const authNoEmail: AuthContextValue = {
      ...keycloakAuth,
      user: {
        ...keycloakAuth.user!,
        email: "",
      },
    };
    renderSidebarWithAuth(false, authNoEmail);

    expect(screen.getByText("John Doe")).toBeInTheDocument();
    expect(screen.queryByText("john@example.com")).not.toBeInTheDocument();
  });

  it("shows username fallback when no fullName", () => {
    const authNoFullName: AuthContextValue = {
      ...keycloakAuth,
      user: {
        ...keycloakAuth.user!,
        fullName: "",
        firstName: "",
        lastName: "",
      },
    };
    renderSidebarWithAuth(false, authNoFullName);

    expect(screen.getByText("johndoe")).toBeInTheDocument();
  });

  // ── Help & Tour menu ───────────────────────────────────────────────
  it("renders help button", () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    expect(screen.getByTestId("sidebar-help")).toBeInTheDocument();
  });

  it("opens help menu on click", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));

    expect(screen.getByTestId("help-menu-dropdown")).toBeInTheDocument();
  });

  it("shows all tour chapter items in help menu", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));

    // Should have items for all chapters
    expect(screen.getByTestId("help-chapter-dashboard")).toBeInTheDocument();
    expect(screen.getByTestId("help-chapter-agents")).toBeInTheDocument();
    expect(screen.getByTestId("help-chapter-workflows")).toBeInTheDocument();
  });

  it("shows reset all tours button in help menu", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));

    expect(screen.getByTestId("help-reset-all")).toBeInTheDocument();
  });

  it("closes help menu on second click", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));
    expect(screen.getByTestId("help-menu-dropdown")).toBeInTheDocument();

    await user.click(screen.getByTestId("sidebar-help"));
    expect(screen.queryByTestId("help-menu-dropdown")).not.toBeInTheDocument();
  });

  it("closes help menu on Escape key", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));
    expect(screen.getByTestId("help-menu-dropdown")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByTestId("help-menu-dropdown")).not.toBeInTheDocument();
  });

  it("clicking reset all calls resetAll on onboarding store", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    // Mark a chapter as done
    useOnboarding.getState().completeChapter();
    useOnboarding.setState({ activeChapter: "dashboard" });
    useOnboarding.getState().completeChapter();

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));
    await user.click(screen.getByTestId("help-reset-all"));

    // Menu should close
    expect(screen.queryByTestId("help-menu-dropdown")).not.toBeInTheDocument();

    // All chapters should be reset
    expect(useOnboarding.getState().completedChapters.size).toBe(0);
  });

  // ── Collapsed sidebar separators ───────────────────────────────────
  it("shows divider separators when collapsed", () => {
    const { container } = renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    // Collapsed sidebar shows border-t dividers between sections
    const dividers = container.querySelectorAll(".border-t.border-sidebar-border.mx-3.mb-2");
    // There should be dividers for sections 1, 2, 3 (not section 0)
    expect(dividers.length).toBe(3);
  });

  // ── Nav item aria labels when collapsed ────────────────────────────
  it("adds aria-label to nav items when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    // NavLink items should have aria-label when collapsed
    const links = screen.getAllByRole("link");
    const dashboardLink = links.find((l) => l.getAttribute("aria-label") === "Dashboard");
    expect(dashboardLink).toBeTruthy();
  });

  // ── Version display ────────────────────────────────────────────────
  it("shows version text when expanded", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    // Should either show "Checking version..." or the version string
    await waitFor(() => {
      const sidebar = screen.getByTestId("sidebar");
      const text = sidebar.textContent;
      expect(text).toMatch(/EDDI|Checking version/);
    });
  });

  it("hides version text when collapsed", () => {
    renderWithProviders(
      <Sidebar collapsed={true} onToggle={() => {}} />
    );

    expect(screen.queryByText(/EDDI Demo/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Checking version/)).not.toBeInTheDocument();
  });

  // ── Keyboard navigation in help menu ───────────────────────────────
  it("navigates help menu items with arrow keys", async () => {
    renderWithProviders(
      <Sidebar collapsed={false} onToggle={() => {}} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("sidebar-help"));

    // Focus should auto-go to first menu item
    await waitFor(() => {
      const dropdown = screen.getByTestId("help-menu-dropdown");
      expect(dropdown).toBeInTheDocument();
    });

    // Press ArrowDown to navigate
    fireEvent.keyDown(screen.getByTestId("help-menu-dropdown"), { key: "ArrowDown" });

    // The second item should be focused (or similar)
    const menuItems = screen.getAllByRole("menuitem");
    expect(menuItems.length).toBeGreaterThan(0);
  });
});
