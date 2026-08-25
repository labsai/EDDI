import { describe, it, expect } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { renderPage, userEvent } from "@/test/test-utils";
import { ConnectionDetailPage } from "@/pages/connection-detail";

/**
 * The editor, and the three rules a generated form could not express:
 * `binding` is derived, `name` is immutable, and secrets are references.
 */

function renderDetail(id = "conn1", version = 1) {
  return renderPage(
    `/manage/connections/${id}?version=${version}`,
    <ConnectionDetailPage />,
    "/manage/connections/:id",
  );
}

/** Capture what a save actually puts on the wire. */
function captureSave() {
  const sent: Record<string, unknown>[] = [];
  server.use(
    http.put("*/connectionstore/connections/:id", async ({ request, params }) => {
      sent.push((await request.json()) as Record<string, unknown>);
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `/connectionstore/connections/${params.id}?version=2` },
      });
    }),
  );
  return sent;
}

/**
 * A save whose GET afterwards returns what was PUT.
 *
 * The default handlers serve a fixed fixture, so the refetch that follows a
 * save legitimately replaces the saved text with the fixture's — which makes it
 * impossible to tell "the page settled on the saved document" from "the page
 * reverted". A document store echoes back what it stored, so the mock does too.
 */
function captureSaveWithEcho() {
  const sent: Record<string, unknown>[] = [];
  let stored: Record<string, unknown> | null = null;
  server.use(
    http.put("*/connectionstore/connections/:id", async ({ request, params }) => {
      stored = (await request.json()) as Record<string, unknown>;
      sent.push(stored);
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `/connectionstore/connections/${params.id}?version=2` },
      });
    }),
    http.get("*/connectionstore/connections/:id", ({ request }) => {
      if (new URL(request.url).pathname.endsWith("/descriptors")) return;
      if (stored) return HttpResponse.json(stored);
      return undefined;
    }),
  );
  return sent;
}

/**
 * The page opened with its document ALREADY in the cache.
 *
 * This is the ordinary way in — the list seeds the detail key from its
 * enrichment and links to that exact version — and it is also what a save
 * produces. `config` is therefore defined on the very first render, which is
 * the case the two seeding/reset effects have to survive together.
 *
 * Built by hand rather than through `renderPage`, because the cache has to be
 * primed before the first render and at the app's real `staleTime`: under the
 * shared helper's default of 0 the entry is stale immediately and a refetch
 * papers over the bug.
 */
function renderPreSeeded(config: Record<string, unknown>, version = 1) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
  });
  queryClient.setQueryData(["connections", "conn1", version], config);
  return renderPage(
    `/manage/connections/conn1?version=${version}`,
    <ConnectionDetailPage />,
    "/manage/connections/:id",
    queryClient,
  );
}

const SEEDED = {
  name: "jira",
  description: "seeded from the list",
  authType: "OAUTH2_AUTHORIZATION_CODE",
  binding: "PER_USER",
  allowUnverifiedPrincipal: false,
  oauth: {
    clientId: "x",
    clientSecret: "${vault:s}",
    authorizationEndpoint: "https://auth.example.com/a",
    tokenEndpoint: "https://auth.example.com/t",
    scopes: [],
    extraAuthParams: {},
    clientAuthMethod: "CLIENT_SECRET_BASIC",
    discoveryUrl: null,
  },
  staticAuth: null,
  baseUrlAllowlist: ["https://jira.example.com"],
};

describe("ConnectionDetailPage — opened from a primed cache", () => {
  it("renders the form instead of hanging on the skeleton", async () => {
    // Effects run in declaration order, so with the document already cached the
    // seeding effect and the [id, version] reset both fire in the same pass.
    // Seed-then-reset ends with a null draft, and nothing wakes it up again:
    // the seeding effect only re-runs on a new `config` identity, and
    // structural sharing hands back the *same* object when a refetch equals
    // what is cached. The skeleton would stay until a manual reload.
    renderPreSeeded(SEEDED);

    await waitFor(() =>
      expect(screen.getByTestId("connection-name-input")).toBeInTheDocument(),
    );
    expect(screen.queryByTestId("connection-detail-loading")).not.toBeInTheDocument();
    expect(screen.getByTestId("connection-description-input")).toHaveValue(
      "seeded from the list",
    );
  });

  it("keeps the form up once the settling refetch has been and gone", async () => {
    // Guards the recovery story too: whether or not a background fetch lands,
    // the form must still be there afterwards rather than reverting to null.
    renderPreSeeded(SEEDED);
    await screen.findByTestId("connection-name-input");

    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(screen.getByTestId("connection-name-input")).toBeInTheDocument();
    expect(screen.queryByTestId("connection-detail-loading")).not.toBeInTheDocument();
  });
});

describe("ConnectionDetailPage", () => {
  it("loads the connection into an editable draft", async () => {
    renderDetail();

    const name = await screen.findByTestId("connection-name-input");
    expect(name).toHaveValue("jira");
    expect(screen.getByTestId("connection-client-id")).toHaveValue("0Xy1abcDEF");
  });

  it("disables the name, because every grant is filed under it", async () => {
    // A rename orphans every linked account, and the next connection created
    // under the old name inherits them. The backend refuses; so does the form.
    renderDetail();

    expect(await screen.findByTestId("connection-name-input")).toBeDisabled();
  });

  it("shows the binding as derived rather than offering it as a choice", async () => {
    renderDetail();
    await screen.findByTestId("connection-name-input");

    // One select for the auth type, and no select for the binding: the backend
    // couples them both ways, leaving exactly one legal value per type.
    expect(screen.getByTestId("connection-auth-type-select")).toBeInTheDocument();
    expect(screen.queryByTestId("connection-binding-select")).not.toBeInTheDocument();
  });

  it("offers the unverified-principal flag only for a per-user connection", async () => {
    renderDetail("conn1"); // authorization code → PER_USER
    expect(await screen.findByTestId("allow-unverified-principal")).toBeInTheDocument();
  });

  it("hides that flag for a service connection, where the backend refuses it", async () => {
    renderDetail("conn3"); // STATIC → SERVICE
    await screen.findByTestId("connection-name-input");
    expect(screen.queryByTestId("allow-unverified-principal")).not.toBeInTheDocument();
  });

  it("re-derives the binding when the auth type changes", async () => {
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn3"); // STATIC / SERVICE
    await screen.findByTestId("connection-name-input");

    await user.selectOptions(
      screen.getByTestId("connection-auth-type-select"),
      "OAUTH2_AUTHORIZATION_CODE",
    );
    // Fill what the new type requires, then save.
    await user.type(
      screen.getByTestId("connection-authorization-url"),
      "https://auth.example.com/authorize",
    );
    await user.type(
      screen.getByTestId("connection-token-url"),
      "https://auth.example.com/token",
    );
    await user.type(screen.getByTestId("connection-client-id"), "abc");
    await user.type(
      screen.getByTestId("connection-client-secret-input"),
      // `{{` is userEvent's escape for a literal brace — its keyboard syntax
      // would otherwise read `{vault:secret}` as a modifier.
      "${{vault:secret}",
    );
    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.binding).toBe("PER_USER");
  });

  it("sends only the auth block its type uses", async () => {
    // Both blocks live in the draft so a mis-clicked type switch is reversible;
    // only one is stored, or a STATIC connection keeps a client-secret
    // reference for an OAuth flow it no longer has.
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn3"); // STATIC
    await screen.findByTestId("connection-name-input");

    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.oauth).toBeNull();
    expect(sent[0]!.staticAuth).not.toBeNull();
  });

  it("refuses to save a literal in a reference-only field, and says which", async () => {
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    // A valid reference renders as a chip, not an input — clearing it is what
    // puts the field back into a state where a literal could be typed at all.
    await user.click(screen.getByTestId("connection-client-secret-clear"));
    await user.type(screen.getByTestId("connection-client-secret-input"), "sk-live-abc");
    await user.click(screen.getByTestId("save-connection-btn"));

    // Nothing reached the backend, and the field says why.
    expect(sent).toHaveLength(0);
    expect(
      screen.getByTestId("connection-client-secret-literal-warning"),
    ).toBeInTheDocument();
  });

  it("follows the new version after a save, so the next one does not conflict", async () => {
    const user = userEvent.setup();
    captureSave();
    renderDetail("conn3");
    await screen.findByTestId("connection-name-input");

    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() =>
      expect(screen.getByText(/^v2$/)).toBeInTheDocument(),
    );
  });

  it("saves a scope typed but never committed with Enter", async () => {
    // The worst of the silent-loss bugs: Save reported success, the text stayed
    // visible in the box, and the stored connection had no scopes — so every
    // user linked without offline_access and the grants died days later with
    // nothing on screen connecting the two.
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    await user.type(screen.getByTestId("connection-scopes-input"), "offline_access");
    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect((sent[0]!.oauth as { scopes: string[] }).scopes).toContain("offline_access");
  });

  it("saves an origin typed but never committed", async () => {
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn3");
    await screen.findByTestId("connection-name-input");

    await user.type(
      screen.getByTestId("connection-origins-input"),
      "https://extra.example.com",
    );
    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.baseUrlAllowlist).toContain("https://extra.example.com");
  });

  it("drops the BASIC fields when the type is switched to STATIC", async () => {
    // conn5 is BASIC with a `${vault:legacy-crm-password}` passwordRef. After
    // the switch that pointer has no flow that reads it, and carrying it across
    // re-arms it on any later switch back.
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn5");
    await screen.findByTestId("connection-name-input");

    await user.selectOptions(
      screen.getByTestId("connection-auth-type-select"),
      "STATIC",
    );
    // An empty template opens in the guided view, which is the path a real
    // author takes: a prefix plus a stored secret.
    await user.type(screen.getByTestId("connection-header-value-prefix"), "Bearer ");
    await user.type(
      screen.getByTestId("connection-header-value-secret-input"),
      "${{vault:legacy-crm-password}",
    );
    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() => expect(sent).toHaveLength(1));
    const staticAuth = sent[0]!.staticAuth as Record<string, unknown>;
    expect(staticAuth.passwordRef).toBeUndefined();
    expect(staticAuth.username).toBeUndefined();
    expect(staticAuth.valueTemplate).toContain("${vault:legacy-crm-password}");
  });

  it("does not let a background refetch overwrite edits in progress", async () => {
    // A window-focus refetch used to replace the whole draft with the server
    // copy: no warning, no undo.
    const user = userEvent.setup();
    const { queryClient } = renderDetail("conn3");
    await screen.findByTestId("connection-name-input");

    const description = screen.getByTestId("connection-description-input");
    await user.clear(description);
    await user.type(description, "edited locally");

    await queryClient.refetchQueries({ queryKey: ["connections"] });

    await waitFor(() => expect(description).toHaveValue("edited locally"));
  });

  it("accepts a refetch when the form has no unsaved edits", async () => {
    // The other half of the rule: a clean form must still pick up the server's
    // newer copy, or the guard becomes a stale-data bug of its own.
    const { queryClient } = renderDetail("conn3");
    await screen.findByTestId("connection-name-input");

    server.use(
      http.get("*/connectionstore/connections/:id", ({ request }) => {
        if (new URL(request.url).pathname.endsWith("/descriptors")) return;
        return HttpResponse.json({
          name: "amplitude",
          description: "changed on the server",
          authType: "STATIC",
          binding: "SERVICE",
          allowUnverifiedPrincipal: false,
          oauth: null,
          staticAuth: {
            headerName: "Authorization",
            valueTemplate: "Bearer ${vault:amplitude-key}",
          },
          baseUrlAllowlist: ["https://amplitude.com"],
        });
      }),
    );
    await queryClient.refetchQueries({ queryKey: ["connections"] });

    await waitFor(() =>
      expect(screen.getByTestId("connection-description-input")).toHaveValue(
        "changed on the server",
      ),
    );
  });

  it("asks before an in-app navigation would discard unsaved edits", async () => {
    const user = userEvent.setup();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    await user.type(
      screen.getByTestId("connection-description-input"),
      " and more",
    );
    // The form plants this link inside its own binding explainer, so it is a
    // one-click route out of a half-finished document.
    await user.click(screen.getByTestId("connection-linked-accounts-link"));

    // The guard dialog, not the page behind it.
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
  });

  it("asks before the back link would discard unsaved edits", async () => {
    // The most prominent exit on the page. It was a plain <Link> while the
    // obscure linked-accounts link went through the guard, so the one route
    // everybody takes was the one route that dropped the draft silently.
    const user = userEvent.setup();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    await user.type(
      screen.getByTestId("connection-description-input"),
      " and more",
    );
    await user.click(screen.getByTestId("back-to-list"));

    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
  });

  it("lets the back link through when there is nothing to lose", async () => {
    const user = userEvent.setup();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    await user.click(screen.getByTestId("back-to-list"));

    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("keeps the form dirty when the save is REFUSED", async () => {
    // The regression that matters most on this page. Committing the baseline
    // before the request resolved made a failed save look clean: `isDirty` went
    // false, the guard stopped guarding, and the next refetch replaced edits
    // the backend had just rejected. The guard exists for exactly this case.
    const user = userEvent.setup();
    server.use(
      http.put("*/connectionstore/connections/:id", () =>
        HttpResponse.json({ message: "nope" }, { status: 400 }),
      ),
    );
    const { queryClient } = renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    const description = screen.getByTestId("connection-description-input");
    await user.clear(description);
    await user.type(description, "edited locally");
    await user.click(screen.getByTestId("save-connection-btn"));

    // Still on screen after the refusal...
    await waitFor(() => expect(description).toHaveValue("edited locally"));

    // ...and still protected: a refetch must not be allowed to overwrite it.
    await queryClient.refetchQueries({ queryKey: ["connections"] });
    await waitFor(() => expect(description).toHaveValue("edited locally"));

    // And the guard still considers the page dirty.
    await user.click(screen.getByTestId("back-to-list"));
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
  });

  it("shows the saved document instead of a skeleton after a save", async () => {
    // A successful PUT moves the page to the new version, which changes the
    // query key. Unseeded, that key has no data: the page fell back to its
    // loading skeleton and re-fetched the document it had just sent.
    const user = userEvent.setup();
    captureSaveWithEcho();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    const description = screen.getByTestId("connection-description-input");
    await user.clear(description);
    await user.type(description, "saved copy");
    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() =>
      expect(screen.getByTestId("connection-description-input")).toHaveValue(
        "saved copy",
      ),
    );

    // The state has to SETTLE, not merely occur. `waitFor` fires on the commit
    // that briefly shows the saved value, which can be a microtask ahead of the
    // effect flush that clears the draft again — so this assertion in its
    // original form passed on a frame that did not survive.
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(screen.queryByTestId("connection-detail-loading")).not.toBeInTheDocument();
    expect(screen.getByTestId("connection-description-input")).toHaveValue(
      "saved copy",
    );
  });

  it("keeps both rows when a parameter is renamed onto an existing name", async () => {
    // Deriving the rows from Object.entries meant `fromEntries` merged the
    // collision as it was typed: the row count dropped by one and the other
    // value was gone — no warning, no undo, and the row vanished under the
    // cursor mid-word.
    const user = userEvent.setup();
    renderDetail("conn1"); // audience + prompt
    await screen.findByTestId("connection-name-input");

    const first = screen.getByTestId("connection-param-name-0");
    await user.clear(first);
    await user.type(first, "prompt");

    expect(screen.getByTestId("connection-param-name-0")).toHaveValue("prompt");
    expect(screen.getByTestId("connection-param-name-1")).toHaveValue("prompt");
    expect(screen.getByTestId("connection-param-duplicate-0")).toBeInTheDocument();
  });

  it("does not send an empty parameter row", async () => {
    // "Add parameter" then thinking better of it used to store {"": ""}.
    const user = userEvent.setup();
    const sent = captureSave();
    renderDetail("conn1");
    await screen.findByTestId("connection-name-input");

    await user.click(screen.getByTestId("connection-add-param"));
    await user.click(screen.getByTestId("save-connection-btn"));

    await waitFor(() => expect(sent).toHaveLength(1));
    const params = (sent[0]!.oauth as { extraAuthParams: Record<string, string> })
      .extraAuthParams;
    expect(Object.keys(params)).not.toContain("");
    expect(params).toEqual({ audience: "api.atlassian.com", prompt: "consent" });
  });

  it("replaces the page only when the INITIAL load fails", async () => {
    server.use(
      http.get("*/connectionstore/connections/:id", ({ request }) => {
        if (new URL(request.url).pathname.endsWith("/descriptors")) return;
        return new HttpResponse(null, { status: 500 });
      }),
    );
    renderDetail();

    expect(await screen.findByTestId("connection-detail-error")).toBeInTheDocument();
  });
});
