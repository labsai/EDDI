import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TopBar } from "@/components/layout/top-bar";
import { AuthContext, type AuthContextValue } from "@/components/auth/auth-context";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";

/** Render TopBar with custom auth and route */
function renderTopBarWithAuth(
  authValue: AuthContextValue,
  opts: {
    initialRoute?: string;
    sidebarVisible?: boolean;
    onMenuClick?: () => void;
  } = {}
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter initialEntries={[opts.initialRoute ?? "/manage"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-topbar-test">
          <AuthContext.Provider value={authValue}>
            <TopBar
              onMenuClick={opts.onMenuClick ?? vi.fn()}
              sidebarVisible={opts.sidebarVisible ?? false}
            />
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
    username: "janedoe",
    firstName: "Jane",
    lastName: "Doe",
    email: "jane@example.com",
    fullName: "Jane Doe",
  },
  roles: ["admin"],
  method: "keycloak",
  login: vi.fn(),
  logout: vi.fn(),
};

const guestAuth: AuthContextValue = {
  authenticated: true,
  loading: false,
  user: null,
  roles: [],
  method: "none",
  login: vi.fn(),
  logout: vi.fn(),
};

import i18n from "@/i18n/config";

describe("TopBar", () => {
  beforeEach(async () => {
    localStorage.clear();
    await i18n.changeLanguage("en");
  });

  // ── Basic rendering ────────────────────────────────────────────────
  it("renders theme toggle buttons", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("theme-light")).toBeInTheDocument();
    expect(screen.getByTestId("theme-dark")).toBeInTheDocument();
    expect(screen.getByTestId("theme-system")).toBeInTheDocument();
  });

  it("renders language selector", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("language-selector")).toBeInTheDocument();
  });

  it("renders mobile menu toggle", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("mobile-menu-toggle")).toBeInTheDocument();
  });

  it("changes theme on button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    const darkBtn = screen.getByTestId("theme-dark");
    await user.click(darkBtn);

    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });

  it("renders platform status indicator", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("platform-status")).toBeInTheDocument();
  });

  // ── Theme toggle ───────────────────────────────────────────────────
  it("sets light theme on light button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    // First set dark, then set light
    await user.click(screen.getByTestId("theme-dark"));
    expect(document.documentElement.classList.contains("dark")).toBe(true);

    await user.click(screen.getByTestId("theme-light"));
    expect(document.documentElement.classList.contains("light")).toBe(true);
  });

  it("theme buttons have aria-pressed attributes", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    // Default theme is "light" in test, so light should be pressed
    const lightBtn = screen.getByTestId("theme-light");
    expect(lightBtn).toHaveAttribute("aria-pressed", "true");

    const darkBtn = screen.getByTestId("theme-dark");
    expect(darkBtn).toHaveAttribute("aria-pressed", "false");
  });

  // ── Language selector ──────────────────────────────────────────────
  it("language selector has 11 language options", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    const select = screen.getByTestId("language-selector") as HTMLSelectElement;
    const options = select.querySelectorAll("option");
    expect(options.length).toBe(11);
  });

  it("changes language on selector change", async () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    const select = screen.getByTestId("language-selector") as HTMLSelectElement;
    fireEvent.change(select, { target: { value: "de" } });

    expect(select.value).toBe("de");
  });

  // ── Mobile menu ────────────────────────────────────────────────────
  it("calls onMenuClick when mobile menu button is clicked", async () => {
    const onMenuClick = vi.fn();
    renderWithProviders(
      <TopBar onMenuClick={onMenuClick} sidebarVisible={false} />
    );

    const user = userEvent.setup();
    await user.click(screen.getByTestId("mobile-menu-toggle"));
    expect(onMenuClick).toHaveBeenCalledTimes(1);
  });

  it("hides mobile menu button when sidebar is visible", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={true} />
    );

    const btn = screen.getByTestId("mobile-menu-toggle");
    expect(btn.className).toContain("hidden");
  });

  // ── Breadcrumbs ────────────────────────────────────────────────────
  it("shows Dashboard breadcrumb on root route", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
      { initialRoute: "/manage" }
    );

    // Should have Dashboard as breadcrumb
    expect(screen.getByText("Dashboard")).toBeInTheDocument();
  });

  it("shows multi-level breadcrumbs on nested routes", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
      { initialRoute: "/manage/agents" }
    );

    expect(screen.getByText("Dashboard")).toBeInTheDocument();
    expect(screen.getByText("Agents")).toBeInTheDocument();
  });

  it("shows resource breadcrumbs", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
      { initialRoute: "/manage/resources" }
    );

    expect(screen.getByText("Resources")).toBeInTheDocument();
  });

  it("truncates MongoDB-style IDs in breadcrumbs", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
      { initialRoute: "/manage/agents/aabbccddeeff112233445566" }
    );

    // 24-hex-char ID should be truncated to first 8 chars + …
    expect(screen.getByText("aabbccdd…")).toBeInTheDocument();
  });

  it("last breadcrumb is styled as current page", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
      { initialRoute: "/manage/agents" }
    );

    const agentsCrumb = screen.getByText("Agents");
    expect(agentsCrumb).toHaveAttribute("aria-current", "page");
  });

  // ── User dropdown ──────────────────────────────────────────────────
  it("shows user menu trigger when auth is keycloak", () => {
    renderTopBarWithAuth(keycloakAuth);

    expect(screen.getByTestId("user-menu-trigger")).toBeInTheDocument();
  });

  it("hides user menu trigger when auth is none", () => {
    renderTopBarWithAuth(guestAuth);

    expect(screen.queryByTestId("user-menu-trigger")).not.toBeInTheDocument();
  });

  it("shows user initials on the trigger button", () => {
    renderTopBarWithAuth(keycloakAuth);

    // "Jane Doe" → "JD"
    expect(screen.getByText("JD")).toBeInTheDocument();
  });

  it("opens user dropdown on click", async () => {
    renderTopBarWithAuth(keycloakAuth);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));

    expect(screen.getByTestId("user-menu-dropdown")).toBeInTheDocument();
    expect(screen.getByText("Jane Doe")).toBeInTheDocument();
    expect(screen.getByText("jane@example.com")).toBeInTheDocument();
  });

  it("shows logout button in dropdown", async () => {
    renderTopBarWithAuth(keycloakAuth);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));

    expect(screen.getByTestId("user-menu-logout")).toBeInTheDocument();
  });

  it("calls logout when logout button is clicked", async () => {
    const logoutFn = vi.fn();
    renderTopBarWithAuth({ ...keycloakAuth, logout: logoutFn });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));
    await user.click(screen.getByTestId("user-menu-logout"));

    expect(logoutFn).toHaveBeenCalledTimes(1);
  });

  it("closes dropdown after logout click", async () => {
    renderTopBarWithAuth(keycloakAuth);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));
    expect(screen.getByTestId("user-menu-dropdown")).toBeInTheDocument();

    await user.click(screen.getByTestId("user-menu-logout"));
    expect(screen.queryByTestId("user-menu-dropdown")).not.toBeInTheDocument();
  });

  it("closes dropdown on Escape key", async () => {
    renderTopBarWithAuth(keycloakAuth);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));
    expect(screen.getByTestId("user-menu-dropdown")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByTestId("user-menu-dropdown")).not.toBeInTheDocument();
  });

  it("closes dropdown on outside click", async () => {
    renderTopBarWithAuth(keycloakAuth);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));
    expect(screen.getByTestId("user-menu-dropdown")).toBeInTheDocument();

    // Click outside (on the body)
    await user.click(document.body);
    expect(screen.queryByTestId("user-menu-dropdown")).not.toBeInTheDocument();
  });

  it("user menu trigger has aria-expanded attribute", async () => {
    renderTopBarWithAuth(keycloakAuth);

    const trigger = screen.getByTestId("user-menu-trigger");
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    const user = userEvent.setup();
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
  });

  it("shows username as fallback when no fullName", () => {
    renderTopBarWithAuth({
      ...keycloakAuth,
      user: { ...keycloakAuth.user!, fullName: "" },
    });

    const trigger = screen.getByTestId("user-menu-trigger");
    expect(trigger).toHaveAttribute("title", "janedoe");
  });

  it("handles user without email in dropdown", async () => {
    renderTopBarWithAuth({
      ...keycloakAuth,
      user: { ...keycloakAuth.user!, email: "" },
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("user-menu-trigger"));

    expect(screen.getByText("Jane Doe")).toBeInTheDocument();
    expect(screen.queryByText("jane@example.com")).not.toBeInTheDocument();
  });

  // ── Breadcrumb label map coverage ──────────────────────────────────
  it("maps known route segments to labels", () => {
    const routes = [
      { route: "/manage/chat", label: "Chat" },
      { route: "/manage/logs", label: "Logs" },
      { route: "/manage/secrets", label: "Secrets" },
      { route: "/manage/groups", label: "Groups" },
      { route: "/manage/gdpr", label: "Privacy" },
    ];

    for (const { route, label } of routes) {
      const { unmount } = renderWithProviders(
        <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
        { initialRoute: route }
      );
      expect(screen.getByText(label)).toBeInTheDocument();
      unmount();
    }
  });

  it("strips 'view' suffix from unknown segments", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />,
      { initialRoute: "/manage/workflowview" }
    );

    // workflowview maps to nav.packages via labelMap
    expect(screen.getByText("Workflows")).toBeInTheDocument();
  });
});
