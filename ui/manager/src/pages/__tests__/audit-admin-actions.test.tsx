import { describe, it, expect, afterEach } from "vitest";
import { screen, waitFor, within, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AuditPage } from "@/pages/audit";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderAdminActions() {
  return renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit?view=admin" });
}

afterEach(() => server.resetHandlers());

describe("Audit page — tabs", () => {
  it("opens on the pipeline trail and switches to administrative actions", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit" });
    expect(screen.getByTestId("audit-tab-trail")).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByTestId("admin-actions-view")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("audit-tab-admin"));

    expect(screen.getByTestId("audit-tab-admin")).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByTestId("admin-actions-view")).toBeInTheDocument();
  });

  it("moves between tabs with the arrow keys", async () => {
    renderWithProviders(<AuditPage />, { initialRoute: "/manage/audit" });
    fireEvent.keyDown(screen.getByTestId("audit-tab-trail"), { key: "ArrowRight" });
    await waitFor(() => expect(screen.getByTestId("audit-tab-admin")).toHaveAttribute("aria-selected", "true"));
  });
});

describe("Administrative actions", () => {
  it("lists every recorded action with actor, method, path, endpoint and status", async () => {
    renderAdminActions();
    const row = await screen.findByTestId("admin-action-adm-1");
    expect(row).toHaveTextContent("alice");
    expect(row).toHaveTextContent("POST");
    expect(row).toHaveTextContent("/administration/production/deploy/agent1");
    expect(row).toHaveTextContent("RestAgentAdministration#deployAgent");
    expect(within(row).getByTestId("admin-action-status-adm-1")).toHaveTextContent("202");
    expect(screen.getByTestId("admin-action-adm-4")).toHaveTextContent("403");
  });

  it("sends the actor to the server and shows only that actor's rows", async () => {
    let lastActor: string | null = "unset";
    server.use(
      http.get("*/auditstore/admin-actions", ({ request }) => {
        lastActor = new URL(request.url).searchParams.get("actor");
        return HttpResponse.json([]);
      }),
    );
    const user = userEvent.setup();
    renderAdminActions();
    await waitFor(() => expect(lastActor).toBeNull());
    await user.type(screen.getByTestId("admin-actions-actor"), "bob");
    await user.click(screen.getByTestId("admin-actions-apply"));
    await waitFor(() => expect(lastActor).toBe("bob"));
  });

  it("narrows the loaded rows by method", async () => {
    renderAdminActions();
    await screen.findByTestId("admin-action-adm-1");
    fireEvent.change(screen.getByTestId("admin-actions-method"), { target: { value: "DELETE" } });
    expect(screen.getByTestId("admin-action-adm-3")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-action-adm-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("admin-actions-count")).toHaveTextContent("1 of 5");
  });

  it("narrows the loaded rows by time", async () => {
    renderAdminActions();
    await screen.findByTestId("admin-action-adm-1");
    fireEvent.change(screen.getByTestId("admin-actions-window"), { target: { value: "hour" } });
    expect(screen.getByTestId("admin-action-adm-1")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-action-adm-2")).not.toBeInTheDocument();
  });

  it("says when no row matches the filters", async () => {
    renderAdminActions();
    await screen.findByTestId("admin-action-adm-1");
    fireEvent.change(screen.getByTestId("admin-actions-method"), { target: { value: "PATCH" } });
    fireEvent.change(screen.getByTestId("admin-actions-window"), { target: { value: "hour" } });
    expect(screen.getByTestId("empty-state")).toHaveTextContent(/match these filters/);
  });

  it("pages further back with Load older", async () => {
    const page = (from: number, n: number) =>
      Array.from({ length: n }, (_, i) => ({
        id: `p-${from + i}`,
        userId: "alice",
        taskId: "ai.labs.admin",
        taskType: "admin",
        input: { method: "POST", path: `/x/${from + i}`, resource: "R#m" },
        output: { status: 200 },
        actions: ["ADMIN_POST"],
        timestamp: new Date(Date.now() - (from + i) * 60_000).toISOString(),
      }));
    server.use(
      http.get("*/auditstore/admin-actions", ({ request }) => {
        const skip = Number(new URL(request.url).searchParams.get("skip"));
        return HttpResponse.json(skip === 0 ? page(0, 100) : page(100, 3));
      }),
    );
    const user = userEvent.setup();
    renderAdminActions();
    await screen.findByTestId("admin-action-p-0");
    await user.click(screen.getByTestId("admin-actions-load-older"));
    expect(await screen.findByTestId("admin-action-p-102")).toBeInTheDocument();
    expect(screen.queryByTestId("admin-actions-load-older")).not.toBeInTheDocument();
  });

  it("explains a 403: only administrators may read it", async () => {
    server.use(http.get("*/auditstore/admin-actions", () => new HttpResponse(null, { status: 403 })));
    renderAdminActions();
    expect(await screen.findByText(/Only administrators can read administrative actions/)).toBeInTheDocument();
  });

  it("explains a 404: this EDDI does not record them", async () => {
    server.use(http.get("*/auditstore/admin-actions", () => new HttpResponse(null, { status: 404 })));
    renderAdminActions();
    expect(await screen.findByText(/This EDDI does not record administrative actions/)).toBeInTheDocument();
  });

  it("an empty answer (also what an older EDDI gives) says when they are recorded", async () => {
    server.use(http.get("*/auditstore/admin-actions", () => HttpResponse.json([])));
    renderAdminActions();
    expect(await screen.findByTestId("empty-state")).toHaveTextContent(/from version 6.6 on/);
  });

  it("offers a retry on any other failure", async () => {
    server.use(http.get("*/auditstore/admin-actions", () => HttpResponse.json({ message: "boom" }, { status: 500 })));
    renderAdminActions();
    expect(await screen.findByTestId("error-state", {}, { timeout: 5000 })).toBeInTheDocument();
  });
});
