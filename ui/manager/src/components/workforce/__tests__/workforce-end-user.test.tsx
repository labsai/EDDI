import { describe, it, expect } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import type { ReactElement } from "react";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthContext, GUEST_CONTEXT } from "@/components/auth/auth-context";
import { createTestQueryClient } from "@/test/test-utils";
import { MOCK_CATALOG_AGENTS, MOCK_CATALOG_GROUPS } from "@/test/mocks/handlers";
import { AuthorRoute } from "@/components/workforce/author-route";
import { CatalogEmptyState } from "@/components/workforce/catalog-empty-state";
import { EndUserCatalog } from "@/components/workforce/catalog-view";
import { WorkforceTopbar } from "@/components/workforce/workforce-topbar";
import { LandingPage } from "@/pages/landing-page";
import { CatalogListingToggle } from "@/components/shared/catalog-listing-toggle";
import { mockRolesOverride } from "@/lib/mock-roles";

/**
 * Workforce for an end user (phase 2 of the frontend-unification plan): what
 * an `eddi-user` without `eddi-editor` / `eddi-admin` is shown and kept away
 * from. Roles arrive through the auth context, as in a Keycloak session.
 */

function Where() {
  const location = useLocation();
  return <div data-testid="where">{location.pathname}</div>;
}

function renderAs(roles: string[], path: string, routes: Array<[string, ReactElement]>) {
  return render(
    <AuthContext.Provider value={{ ...GUEST_CONTEXT, method: "keycloak", roles }}>
      <MemoryRouter initialEntries={[path]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              {routes.map(([pattern, element]) => (
                <Route key={pattern} path={pattern} element={element} />
              ))}
              <Route path="*" element={<Where />} />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe("AuthorRoute", () => {
  it("sends an eddi-user from team settings to the team's board", () => {
    renderAs(["eddi-user"], "/workforce/grp1/settings", [
      ["/workforce/:boardId/settings", <AuthorRoute><div data-testid="settings" /></AuthorRoute>],
    ]);
    expect(screen.queryByTestId("settings")).toBeNull();
    expect(screen.getByTestId("where")).toHaveTextContent("/workforce/grp1");
  });

  it("sends an eddi-user from the wizard to the dashboard", () => {
    renderAs(["eddi-user"], "/workforce/new", [
      ["/workforce/new", <AuthorRoute><div data-testid="wizard" /></AuthorRoute>],
    ]);
    expect(screen.queryByTestId("wizard")).toBeNull();
    expect(screen.getByTestId("where")).toHaveTextContent("/workforce");
  });

  it("renders the screen for an editor", () => {
    renderAs(["eddi-editor"], "/workforce/new", [
      ["/workforce/new", <AuthorRoute><div data-testid="wizard" /></AuthorRoute>],
    ]);
    expect(screen.getByTestId("wizard")).toBeInTheDocument();
  });
});

describe("WorkforceTopbar (D5)", () => {
  it("shows an admin a link to the operator instead of its drawer", () => {
    renderAs(["eddi-admin"], "/workforce", [["/workforce", <WorkforceTopbar />]]);
    expect(screen.getByTestId("workforce-operator-link")).toHaveAttribute("href", "/manage/operator");
  });

  it("shows an end user and an editor no operator at all", () => {
    for (const roles of [["eddi-user"], ["eddi-editor"]]) {
      const { unmount } = renderAs(roles, "/workforce", [["/workforce", <WorkforceTopbar />]]);
      expect(screen.queryByTestId("workforce-operator-link")).toBeNull();
      unmount();
    }
  });
});

describe("CatalogEmptyState", () => {
  it("tells an author how to list an agent", () => {
    renderAs(["eddi-editor"], "/x", [["/x", <CatalogEmptyState canAuthor />]]);
    expect(screen.getByTestId("catalog-empty-author")).toBeInTheDocument();
    expect(screen.getByTestId("catalog-empty-manage-agents")).toHaveAttribute("href", "/manage/agents");
  });

  it("gives a plain user a neutral message without the mechanics", () => {
    renderAs(["eddi-user"], "/x", [["/x", <CatalogEmptyState canAuthor={false} />]]);
    expect(screen.getByTestId("catalog-empty-user")).toBeInTheDocument();
    expect(screen.queryByTestId("catalog-empty-manage-agents")).toBeNull();
  });
});

describe("EndUserCatalog", () => {
  it("shows exactly the listed agents and teams the catalog returns, without authoring actions", async () => {
    renderAs(["eddi-user"], "/workforce", [["/workforce", <EndUserCatalog />]]);
    for (const agent of MOCK_CATALOG_AGENTS) {
      expect(await screen.findByTestId(`catalog-agent-${agent.agentId}`)).toBeInTheDocument();
    }
    for (const group of MOCK_CATALOG_GROUPS) {
      expect(await screen.findByTestId(`catalog-group-${group.groupId}`)).toBeInTheDocument();
    }
    expect(screen.getAllByTestId(/^catalog-agent-/)).toHaveLength(MOCK_CATALOG_AGENTS.length);
  });
});

describe("LandingPage (D6)", () => {
  it("sends an eddi-user straight to Workforce, even with ?choose", () => {
    renderAs(["eddi-user"], "/welcome?choose", [["/welcome", <LandingPage />]]);
    expect(screen.getByTestId("where")).toHaveTextContent("/workforce");
  });

  it("keeps the chooser for editors and admins", () => {
    for (const roles of [["eddi-editor"], ["eddi-admin"]]) {
      const { unmount } = renderAs(roles, "/welcome?choose", [["/welcome", <LandingPage />]]);
      expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
      unmount();
    }
  });
});

describe("CatalogListingToggle", () => {
  it("reads an absent flag as off and reports the flipped value", () => {
    const changes: boolean[] = [];
    renderAs(["eddi-editor"], "/x", [
      ["/x", <CatalogListingToggle kind="agent" listed={undefined} onChange={(v) => changes.push(v)} />],
    ]);
    const toggle = screen.getByTestId("agent-listed-toggle");
    expect(toggle).toHaveAttribute("aria-checked", "false");
    fireEvent.click(toggle);
    expect(changes).toEqual([true]);
  });
});

describe("mockRolesOverride", () => {
  it("ignores the stored roles unless the mock API is active", () => {
    const win = window as unknown as Record<string, unknown>;
    localStorage.setItem("eddi-mock-roles", "eddi-user");
    try {
      expect(mockRolesOverride()).toBeNull();
      win.__EDDI_MOCK_ACTIVE__ = true;
      expect(mockRolesOverride()).toEqual(["eddi-user"]);
    } finally {
      delete win.__EDDI_MOCK_ACTIVE__;
      localStorage.removeItem("eddi-mock-roles");
    }
  });
});
