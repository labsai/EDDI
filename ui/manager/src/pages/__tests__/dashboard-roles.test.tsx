import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { AuthContext, GUEST_CONTEXT, type AuthContextValue } from "@/components/auth/auth-context";
import { DashboardPage } from "@/pages/dashboard";
import { Sidebar } from "@/components/layout/sidebar";

/**
 * Review 2026-10-02 (live, Keycloak): signed in as an eddi-editor, the
 * dashboard fired three eddi-admin-only requests (coordinator status, vault
 * health, instance id), showed the 403s as "Vault unavailable" and
 * "Coordinator —", and offered the admin-only Logs, Audit and Vault quick
 * actions and sidebar entries.
 */

function as(roles: string[]): AuthContextValue {
  return { ...GUEST_CONTEXT, method: "keycloak", roles, user: null };
}

let adminCalls: string[];

beforeEach(() => {
  adminCalls = [];
  const record = (path: string) => () => {
    adminCalls.push(path);
    return new HttpResponse(null, { status: 403 });
  };
  server.use(
    http.get("*/administration/coordinator/status", record("coordinator")),
    http.get("*/secretstore/secrets/health", record("vault-health")),
    http.get("*/administration/logs/instance-id", record("instance-id")),
    http.get("*/administration/docs", () => HttpResponse.json(["getting-started"])),
  );
});

function renderDashboard(auth: AuthContextValue) {
  return renderWithProviders(
    <AuthContext.Provider value={auth}>
      <DashboardPage />
    </AuthContext.Provider>,
    { initialRoute: "/manage" },
  );
}

describe("Dashboard is role-aware", () => {
  it("an editor gets no admin-only quick actions and no admin-only requests", async () => {
    renderDashboard(as(["eddi-editor", "default-roles-eddi"]));

    expect(await screen.findByTestId("health-vault")).toHaveTextContent("requires admin");
    expect(screen.getByTestId("health-coordinator")).toHaveTextContent("requires admin");
    expect(screen.queryByTestId("quick-action-logs")).not.toBeInTheDocument();
    expect(screen.queryByTestId("quick-action-audit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("quick-action-secrets")).not.toBeInTheDocument();

    // Give the queries time to have fired if they were going to.
    await new Promise((r) => setTimeout(r, 100));
    expect(adminCalls).toEqual([]);
  });

  it("an admin gets the admin quick actions and the health checks", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json({ status: "UP", provider: "local", available: true }),
      ),
    );
    renderDashboard(as(["eddi-admin"]));

    expect(await screen.findByTestId("quick-action-logs")).toBeInTheDocument();
    expect(screen.getByTestId("quick-action-audit")).toBeInTheDocument();
    expect(screen.getByTestId("quick-action-secrets")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("health-vault")).toHaveTextContent("Vault ready"));
  });

  it("says 'requires admin', not 'unavailable', when an admin-looking session still gets a 403", async () => {
    // No EDDI role in the token (roles mapped from another claim): the
    // Manager asks, and reads the refusal as a permission answer.
    renderDashboard(as(["some-other-role"]));
    await waitFor(() => expect(adminCalls).toContain("vault-health"));
    await waitFor(() => expect(screen.getByTestId("health-vault")).toHaveTextContent("requires admin"));
    expect(screen.getByTestId("health-vault")).not.toHaveTextContent("unavailable");
  });

  it("without auth (no OIDC) everything stays offered", async () => {
    renderDashboard(GUEST_CONTEXT);
    expect(await screen.findByTestId("quick-action-logs")).toBeInTheDocument();
    expect(screen.getByTestId("quick-action-secrets")).toBeInTheDocument();
  });
});

describe("Sidebar is role-aware", () => {
  function renderSidebar(auth: AuthContextValue) {
    return renderWithProviders(
      <AuthContext.Provider value={auth}>
        <Sidebar collapsed={false} onToggle={() => {}} />
      </AuthContext.Provider>,
      { initialRoute: "/manage" },
    );
  }

  const ADMIN_ONLY = ["/manage/logs", "/manage/audit", "/manage/secrets", "/manage/coordinator", "/manage/gdpr", "/manage/quotas", "/manage/orphans"];

  it("hides admin-only screens from an editor and keeps the rest", () => {
    const { container } = renderSidebar(as(["eddi-editor"]));
    const nav = within(container);
    const hrefs = nav.getAllByRole("link").map((a) => a.getAttribute("href"));
    for (const path of ADMIN_ONLY) expect(hrefs).not.toContain(path);
    expect(hrefs).toContain("/manage/agents");
    expect(hrefs).toContain("/manage/workflows");
    // IRestConnectionStore lets an editor list and read connections.
    expect(hrefs).toContain("/manage/connections");
  });

  it("hides connections from a role the connection store refuses", () => {
    const { container } = renderSidebar(as(["eddi-user"]));
    const hrefs = within(container).getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).not.toContain("/manage/connections");
  });

  it("lists them for an admin", () => {
    const { container } = renderSidebar(as(["eddi-admin"]));
    const hrefs = within(container).getAllByRole("link").map((a) => a.getAttribute("href"));
    for (const path of ADMIN_ONLY) expect(hrefs).toContain(path);
  });

  it("lists them when the token carries no EDDI role at all", () => {
    const { container } = renderSidebar(as(["offline_access"]));
    const hrefs = within(container).getAllByRole("link").map((a) => a.getAttribute("href"));
    for (const path of ADMIN_ONLY) expect(hrefs).toContain(path);
  });
});
