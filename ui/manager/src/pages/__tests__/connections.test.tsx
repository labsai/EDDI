import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { useLocation } from "react-router-dom";
import { renderPage, userEvent } from "@/test/test-utils";
import { ConnectionsPage } from "@/pages/connections";
import { toast } from "sonner";

const successSpy = vi.spyOn(toast, "success");
beforeEach(() => successSpy.mockClear());

/**
 * The router's own location, rendered so a test can assert on it.
 *
 * `window.location` is not it: these tests run under `MemoryRouter`, which
 * never touches the address bar — so asserting `window.location.search` would
 * pass whether or not the parameter was ever stripped.
 */
function LocationProbe() {
  const location = useLocation();
  return <span data-testid="router-search">{location.search}</span>;
}

/**
 * The admin list, and what it does when the viewer is not an admin.
 *
 * The 403 case is the one that matters: `ConnectionsConfig.defaultReturnTo()`
 * sends *everyone* here after a linking round trip, so a non-admin following
 * that redirect must land on something useful rather than a permission error.
 */

function renderConnections(route = "/manage/connections") {
  return renderPage(
    route,
    <>
      <ConnectionsPage />
      <LocationProbe />
    </>,
    "/manage/connections",
  );
}

describe("ConnectionsPage", () => {
  it("lists the connections with their auth type and binding", async () => {
    // Asserted on testids, not on the names: a per-user connection legitimately
    // appears twice on this page — once as a config card, once as a row in the
    // linked-accounts panel below it.
    renderConnections();

    expect(await screen.findByTestId("connection-card-conn1")).toBeInTheDocument();
    expect(screen.getByTestId("connection-card-conn3")).toBeInTheDocument();
    expect(
      screen.getAllByTestId("auth-type-OAUTH2_AUTHORIZATION_CODE").length,
    ).toBeGreaterThan(0);
    expect(screen.getAllByTestId("binding-PER_USER").length).toBeGreaterThan(0);
  });

  it("filters on name, description, auth type and origin", async () => {
    const user = userEvent.setup();
    renderConnections();
    await screen.findByTestId("connection-card-conn1");

    // Matches jira's allowlisted origin, nothing else.
    await user.type(screen.getByTestId("connection-search"), "atlassian");
    await waitFor(() =>
      expect(screen.queryByTestId("connection-card-conn3")).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId("connection-card-conn1")).toBeInTheDocument();
  });

  it("explains a 403 and still shows the viewer's own linked accounts", async () => {
    server.use(
      http.get(
        "*/connectionstore/connections/descriptors",
        () => new HttpResponse(null, { status: 403 }),
      ),
    );
    renderConnections();

    expect(await screen.findByTestId("connections-forbidden")).toBeInTheDocument();
    // Degrades, rather than replacing the page: the per-user panel needs no role.
    expect(screen.getByTestId("linked-accounts-panel")).toBeInTheDocument();
    // And nothing invites an action the viewer cannot take.
    expect(screen.queryByTestId("create-connection-btn")).not.toBeInTheDocument();
  });

  it("shows an error state when the list genuinely fails to load", async () => {
    server.use(
      http.get(
        "*/connectionstore/connections/descriptors",
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    renderConnections();

    expect(await screen.findByTestId("error-state")).toBeInTheDocument();
  });

  it("offers a create action from the empty state", async () => {
    server.use(
      http.get("*/connectionstore/connections/descriptors", () => HttpResponse.json([])),
    );
    renderConnections();

    expect(await screen.findByTestId("empty-state")).toBeInTheDocument();
  });

  it("announces a completed link and strips the parameter from the URL", async () => {
    // Refreshing after a round trip must not re-announce an outcome that is no
    // longer happening.
    renderConnections("/manage/connections?connected=jira&version=2");

    await screen.findByTestId("connection-card-conn1");
    await waitFor(() =>
      expect(screen.getByTestId("router-search")).not.toHaveTextContent("connected"),
    );
    // Only the two parameters this owns are stripped; anything else on the URL
    // belongs to the page.
    expect(screen.getByTestId("router-search")).toHaveTextContent("version=2");
  });

  it("does not congratulate the user on a link that never happened", async () => {
    // `?connected=<name>` is a plain URL parameter, so anyone can hand a signed-in
    // user a link that claims a grant was created. Confirming the name against
    // the refreshed list is what makes the success claim mean anything.
    server.use(http.get("*/connections/mine", () => HttpResponse.json([])));
    renderConnections("/manage/connections?connected=payroll");

    await screen.findByTestId("connection-card-conn1");
    await waitFor(() =>
      expect(screen.getByTestId("router-search")).not.toHaveTextContent("connected"),
    );
    expect(successSpy).not.toHaveBeenCalled();
  });

  it("still announces a link the refreshed list confirms", async () => {
    // The other half: the check must not silence a real success.
    server.use(
      http.get("*/connections/mine", () =>
        HttpResponse.json([
          {
            connection: "jira",
            status: "ACTIVE",
            expiresAt: null,
            scopes: null,
            connectedAt: "2026-01-05T10:00:00Z",
          },
        ]),
      ),
    );
    renderConnections("/manage/connections?connected=jira");

    await waitFor(() => expect(successSpy).toHaveBeenCalled());
  });

  it("only offers Connect for the per-user connections", async () => {
    renderConnections();

    // amplitude is SERVICE-bound: there is no per-user account to link.
    await screen.findByTestId("connection-card-conn3");
    expect(screen.queryByTestId("connect-amplitude")).not.toBeInTheDocument();
  });
});
