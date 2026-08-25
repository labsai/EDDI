import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { toast } from "sonner";
import { navigateAway } from "@/lib/navigate-away";
import { LinkedAccountsPanel } from "@/components/connections/linked-accounts-panel";

/**
 * The one place the app leaves itself, mocked at its own module.
 *
 * Redefining `window.location` works here only because Vitest builds its own
 * window — jsdom 26 declares `location` non-configurable, so the stub was one
 * environment change away from throwing before any assertion ran.
 */
vi.mock("@/lib/navigate-away", () => ({ navigateAway: vi.fn() }));
const assign = vi.mocked(navigateAway);

/** Toasts are the only place the coded errors surface, so they are asserted on. */
const toastSpy = { error: vi.spyOn(toast, "error") };

/**
 * The per-user panel, and the four answers `/connections/mine` can give.
 *
 * Two of them are *states*, not errors: a deployment with
 * `eddi.connections.enabled=false` is not broken, and a deployment without OIDC
 * is not broken either. Rendering an error box over an intentional
 * configuration is the failure these tests exist to prevent.
 */

const ONE_ACTIVE = [
  {
    connection: "jira",
    status: "ACTIVE",
    expiresAt: null,
    scopes: ["read:jira-work"],
    connectedAt: "2026-01-05T10:00:00Z",
  },
];

function mine(body: object | null, status = 200) {
  server.use(
    http.get("*/connections/mine", () =>
      status === 200
        ? HttpResponse.json(body)
        : new HttpResponse(null, { status }),
    ),
  );
}

beforeEach(() => {
  toastSpy.error.mockClear();
  assign.mockClear();
});

describe("LinkedAccountsPanel", () => {
  it("lists a linked account with its status and scopes", async () => {
    mine(ONE_ACTIVE);
    renderWithProviders(<LinkedAccountsPanel />);

    expect(await screen.findByTestId("linked-account-jira")).toBeInTheDocument();
    expect(screen.getByTestId("grant-status-ACTIVE")).toBeInTheDocument();
    expect(screen.getByText("read:jira-work")).toBeInTheDocument();
  });

  it("says linking is switched off rather than showing an error", async () => {
    // A 404 here means the feature is disabled — or that the backend predates
    // it, which from the user's side is the same fact.
    mine(null, 404);
    renderWithProviders(<LinkedAccountsPanel />);

    expect(await screen.findByTestId("connections-disabled")).toBeInTheDocument();
    expect(screen.queryByTestId("error-state")).not.toBeInTheDocument();
  });

  it("asks the viewer to sign in when there is no verified identity", async () => {
    mine(null, 403);
    renderWithProviders(<LinkedAccountsPanel />);

    expect(await screen.findByTestId("connections-no-identity")).toBeInTheDocument();
    expect(screen.queryByTestId("error-state")).not.toBeInTheDocument();
  });

  it("shows a real error state for a real failure", async () => {
    mine(null, 500);
    renderWithProviders(<LinkedAccountsPanel />);

    expect(await screen.findByTestId("error-state")).toBeInTheDocument();
  });

  it("explains the empty case instead of leaving a blank panel", async () => {
    mine([]);
    renderWithProviders(<LinkedAccountsPanel />);

    expect(await screen.findByTestId("empty-state")).toBeInTheDocument();
  });

  it("offers Connect only for a connection that is not already linked", async () => {
    mine(ONE_ACTIVE);
    renderWithProviders(
      <LinkedAccountsPanel
        connectable={[{ name: "jira" }, { name: "google-drive" }]}
      />,
    );

    expect(await screen.findByTestId("linked-account-jira")).toBeInTheDocument();
    expect(screen.getByTestId("connect-google-drive")).toBeInTheDocument();
    // Matched on the connection NAME, which is what a grant is filed under.
    expect(screen.queryByTestId("connect-jira")).not.toBeInTheDocument();
  });

  it("gives Reconnect the emphasis only where reconnecting is the fix", async () => {
    // EXPIRED refreshes itself on the next call, so a prominent "Reconnect"
    // there would cost the user a consent screen to fix nothing.
    mine([
      { connection: "a", status: "EXPIRED", expiresAt: null, scopes: null, connectedAt: null },
      { connection: "b", status: "REFRESH_FAILED", expiresAt: null, scopes: null, connectedAt: null },
    ]);
    renderWithProviders(<LinkedAccountsPanel />);

    await screen.findByTestId("linked-account-a");
    expect(screen.getByTestId("grant-status-EXPIRED")).toBeInTheDocument();
    expect(screen.getByTestId("grant-status-REFRESH_FAILED")).toBeInTheDocument();
    // Both rows can be reconnected; only the terminal one is styled as urgent.
    expect(screen.getByTestId("reconnect-a")).toBeInTheDocument();
    expect(screen.getByTestId("reconnect-b")).toBeInTheDocument();
  });

  it("keeps showing real accounts when a background refetch fails", async () => {
    // The failure states are definitive statements about the deployment. A
    // lapsed token or a moment offline is neither, and both used to render on
    // top of a working list.
    mine(ONE_ACTIVE);
    const { queryClient } = renderWithProviders(<LinkedAccountsPanel />);
    await screen.findByTestId("linked-account-jira");

    mine(null, 403);
    await queryClient.refetchQueries({ queryKey: ["connections", "mine"] });

    await waitFor(() =>
      expect(screen.getByTestId("linked-account-jira")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("connections-no-identity")).not.toBeInTheDocument();
    expect(screen.queryByTestId("error-state")).not.toBeInTheDocument();
  });

  it("confirms before unlinking, then calls the backend", async () => {
    mine(ONE_ACTIVE);
    let deleted: string | null = null;
    server.use(
      http.delete("*/connections/:name/grant", ({ params }) => {
        deleted = params.name as string;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel />);

    await user.click(await screen.findByTestId("unlink-jira"));
    // The confirm dialog stands between the click and the revocation.
    expect(deleted).toBeNull();

    await user.click(screen.getByRole("button", { name: /unlink/i }));
    await waitFor(() => expect(deleted).toBe("jira"));
  });
});

describe("LinkedAccountsPanel — starting a link", () => {
  it("sends the browser to the provider with a top-level navigation", async () => {
    // Not a popup and not an iframe: the nonce cookie binding the flow to this
    // browser is SameSite=Lax, which admits a top-level GET return and nothing
    // else.
    mine([]);
    let returnTo: string | null = null;
    server.use(
      http.post("*/connections/:name/authorize", ({ request }) => {
        returnTo = new URL(request.url).searchParams.get("returnTo");
        return HttpResponse.json({
          authorizationUrl: "https://provider.example/authorize?x=1",
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel connectable={[{ name: "jira" }]} />, {
      initialRoute: "/manage/linked-accounts",
    });

    await user.click(await screen.findByTestId("connect-jira"));

    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith("https://provider.example/authorize?x=1"),
    );
    // The page it came from, so the round trip lands back where it started.
    expect(returnTo).toBe("/manage/linked-accounts");
  });

  it("translates a coded failure instead of toasting English at everyone", async () => {
    // `ConnectionsError.message` is a hardcoded English fallback; the `code` is
    // the whole reason it exists. Toasting the message put English in front of
    // every non-English user on a screen whose body renders the same fact
    // translated.
    mine([]);
    // 503 is the unambiguous "feature is off" answer on this route; a 404 here
    // means the connection is gone, and carries the backend's own message.
    server.use(
      http.post(
        "*/connections/:name/authorize",
        () => new HttpResponse(null, { status: 503 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel connectable={[{ name: "jira" }]} />);

    await user.click(await screen.findByTestId("connect-jira"));

    // The i18n key's English default, not the api layer's hardcoded sentence.
    await waitFor(() =>
      expect(toastSpy.error).toHaveBeenCalledWith(
        "Account linking is switched off",
      ),
    );
  });

  it("passes a deleted connection's own message through instead", async () => {
    mine([]);
    server.use(
      http.post(
        "*/connections/:name/authorize",
        () => new HttpResponse("No connection named 'jira'.", { status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel connectable={[{ name: "jira" }]} />);

    await user.click(await screen.findByTestId("connect-jira"));

    await waitFor(() =>
      expect(toastSpy.error).toHaveBeenCalledWith("No connection named 'jira'."),
    );
  });

  it("frees the buttons again when the browser comes Back from the provider", async () => {
    // `leavingFor` is deliberately left set after navigateAway — while the page
    // really is leaving, the lock and the spinner should hold. But Back from
    // the consent screen restores this page from the back/forward cache with
    // its JS state intact, so the flag survived a navigation that never
    // happened: every button stayed disabled and one row span forever.
    mine([]);
    server.use(
      http.post("*/connections/:name/authorize", () =>
        HttpResponse.json({ authorizationUrl: "https://provider.example/a" }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <LinkedAccountsPanel connectable={[{ name: "jira" }, { name: "drive" }]} />,
    );

    await user.click(await screen.findByTestId("connect-jira"));
    await waitFor(() => expect(assign).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId("connect-drive")).toBeDisabled());

    // A bfcache restore, which is the only thing `persisted` distinguishes.
    const restore = new Event("pageshow") as Event & { persisted?: boolean };
    Object.defineProperty(restore, "persisted", { value: true });
    window.dispatchEvent(restore);

    await waitFor(() =>
      expect(screen.getByTestId("connect-drive")).not.toBeDisabled(),
    );
    expect(screen.getByTestId("connect-jira")).not.toBeDisabled();
  });

  it("keeps the buttons locked on a fresh load, which is not a restore", async () => {
    // `persisted: false` is an ordinary navigation. Clearing the lock on it
    // would defeat the lock during the moment the page is actually leaving.
    mine([]);
    server.use(
      http.post("*/connections/:name/authorize", () =>
        HttpResponse.json({ authorizationUrl: "https://provider.example/a" }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <LinkedAccountsPanel connectable={[{ name: "jira" }, { name: "drive" }]} />,
    );

    await user.click(await screen.findByTestId("connect-jira"));
    await waitFor(() => expect(screen.getByTestId("connect-drive")).toBeDisabled());

    window.dispatchEvent(new Event("pageshow"));

    expect(screen.getByTestId("connect-drive")).toBeDisabled();
  });

  it("allows only one authorize at a time", async () => {
    // Two in-flight flows both call window.location.assign and the last to
    // resolve wins — which need not be the one the spinner is on.
    mine([
      { connection: "a", status: "REVOKED", expiresAt: null, scopes: null, connectedAt: null },
      { connection: "b", status: "REVOKED", expiresAt: null, scopes: null, connectedAt: null },
    ]);
    let authorizeCalls = 0;
    server.use(
      http.post("*/connections/:name/authorize", async () => {
        authorizeCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 50));
        return HttpResponse.json({ authorizationUrl: "https://provider.example/x" });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel />);

    await user.click(await screen.findByTestId("reconnect-a"));
    // Revoked, so no confirmation stands in the way — the second row must be
    // locked out by the in-flight one instead.
    expect(screen.getByTestId("reconnect-b")).toBeDisabled();

    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1));
    expect(authorizeCalls).toBe(1);
  });

  it("confirms before reconnecting an account that is working", async () => {
    // Reconnect sits one gap from Unlink and throws the whole tab out to a
    // provider; on a healthy account there is nothing to repair.
    mine(ONE_ACTIVE);
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel />);

    await user.click(await screen.findByTestId("reconnect-jira"));

    expect(assign).not.toHaveBeenCalled();
    expect(await screen.findByText(/nothing to repair/i)).toBeInTheDocument();
  });

  it("reconnects a broken account without asking, because that IS the fix", async () => {
    mine([
      {
        connection: "jira",
        status: "REFRESH_FAILED",
        expiresAt: null,
        scopes: null,
        connectedAt: null,
      },
    ]);
    server.use(
      http.post("*/connections/:name/authorize", () =>
        HttpResponse.json({ authorizationUrl: "https://provider.example/x" }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel />);

    await user.click(await screen.findByTestId("reconnect-jira"));

    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith("https://provider.example/x"),
    );
  });

  it("does not navigate when authorize fails", async () => {
    mine([]);
    server.use(
      http.post(
        "*/connections/:name/authorize",
        () => new HttpResponse("No connection named 'jira'.", { status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<LinkedAccountsPanel connectable={[{ name: "jira" }]} />);

    await user.click(await screen.findByTestId("connect-jira"));

    await waitFor(() => expect(screen.getByTestId("connect-jira")).toBeEnabled());
    expect(assign).not.toHaveBeenCalled();
  });
});
