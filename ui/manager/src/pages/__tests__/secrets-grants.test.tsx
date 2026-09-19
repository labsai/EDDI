import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretsPage } from "@/pages/secrets";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * The grant editor — changing `allowedAgents` without re-supplying the value.
 *
 * `google-gemini-key` in the mock data is granted to `["agent5", "agent7"]` and
 * `openai-api-key` to `["*"]`, which is the pair of starting states worth
 * covering: a narrowed grant that needs widening, and an open one that needs
 * narrowing.
 */

function renderSecrets() {
  return renderWithProviders(<SecretsPage />, {
    initialRoute: "/manage/secrets",
  });
}

/** Opens the editor for one secret and waits for the dialog. */
async function openEditor(
  user: ReturnType<typeof userEvent.setup>,
  keyName: string,
) {
  await waitFor(() => {
    expect(
      screen.getByTestId(`edit-grant-action-${keyName}`),
    ).toBeInTheDocument();
  });
  await user.click(screen.getByTestId(`edit-grant-action-${keyName}`));
  await waitFor(() => {
    expect(screen.getByTestId("edit-grant-dialog")).toBeInTheDocument();
  });
}

describe("SecretsPage — agent grants", () => {
  it("opens the editor from the Access action", async () => {
    const user = userEvent.setup();
    renderSecrets();

    await openEditor(user, "google-gemini-key");

    expect(screen.getByTestId("grant-mode-all")).toBeInTheDocument();
    expect(screen.getByTestId("grant-mode-specific")).toBeInTheDocument();
  });

  it("opens the editor by clicking the grant shown in the table", async () => {
    // The affordance that matters: the thing displaying the grant is the thing
    // that edits it.
    const user = userEvent.setup();
    renderSecrets();

    await waitFor(() => {
      expect(
        screen.getByTestId("edit-grant-google-gemini-key"),
      ).toBeInTheDocument();
    });
    await user.click(screen.getByTestId("edit-grant-google-gemini-key"));

    expect(await screen.findByTestId("edit-grant-dialog")).toBeInTheDocument();
  });

  it("prefills the existing agent list", async () => {
    const user = userEvent.setup();
    renderSecrets();

    await openEditor(user, "google-gemini-key");

    expect(screen.getByTestId("grant-agent-agent5")).toBeInTheDocument();
    expect(screen.getByTestId("grant-agent-agent7")).toBeInTheDocument();
    expect(screen.getByTestId("grant-mode-specific")).toHaveAttribute(
      "aria-checked",
      "true",
    );
  });

  it("prefills 'all agents' for a wildcard grant, with no agent list", async () => {
    const user = userEvent.setup();
    renderSecrets();

    await openEditor(user, "openai-api-key");

    expect(screen.getByTestId("grant-mode-all")).toHaveAttribute(
      "aria-checked",
      "true",
    );
    expect(screen.queryByTestId("grant-agent-list")).not.toBeInTheDocument();
  });

  it("widens a grant, sending the full list and no value", async () => {
    const user = userEvent.setup();
    let sent: { url: string; body: unknown } | null = null;
    server.use(
      http.put(
        "*/secretstore/secrets/:tenantId/:keyName/grant",
        async ({ request, params }) => {
          const body = await request.json();
          // Only the real write is recorded; the dry-run preview uses the same
          // path and would otherwise overwrite it.
          if (!new URL(request.url).searchParams.get("dryRun")) {
            sent = { url: request.url, body };
          }
          return HttpResponse.json({
            reference: "${vault:google-gemini-key}",
            tenantId: params.tenantId,
            keyName: params.keyName,
            dryRun: false,
            allowedAgents: (body as { allowedAgents: string[] }).allowedAgents,
            previousAllowedAgents: ["agent5", "agent7"],
            grantsAllAgents: false,
            description: null,
            createdAt: new Date().toISOString(),
            lastRotatedAt: null,
            agentsLosingAccess: [],
          });
        },
      ),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.click(screen.getByTestId("grant-save"));

    await waitFor(() => expect(sent).not.toBeNull());
    const request = sent as unknown as {
      url: string;
      body: Record<string, unknown>;
    };
    expect(request.url).toContain("/google-gemini-key/grant");
    // The property that makes this endpoint safe to expose: no plaintext.
    expect(request.body).not.toHaveProperty("value");
    expect(request.body.allowedAgents).toEqual(["agent5", "agent7"]);
  });

  it("removes an agent from the list", async () => {
    const user = userEvent.setup();
    renderSecrets();
    await openEditor(user, "google-gemini-key");

    await user.click(screen.getByTestId("grant-agent-remove-agent7"));

    expect(screen.queryByTestId("grant-agent-agent7")).not.toBeInTheDocument();
    expect(screen.getByTestId("grant-agent-agent5")).toBeInTheDocument();
  });

  it("switching to 'all agents' sends the wildcard", async () => {
    const user = userEvent.setup();
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put(
        "*/secretstore/secrets/:tenantId/:keyName/grant",
        async ({ request, params }) => {
          body = (await request.json()) as Record<string, unknown>;
          return HttpResponse.json({
            reference: "${vault:google-gemini-key}",
            tenantId: params.tenantId,
            keyName: params.keyName,
            dryRun: false,
            allowedAgents: ["*"],
            previousAllowedAgents: ["agent5", "agent7"],
            grantsAllAgents: true,
            description: null,
            createdAt: new Date().toISOString(),
            lastRotatedAt: null,
            agentsLosingAccess: [],
          });
        },
      ),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.click(screen.getByTestId("grant-mode-all"));
    await user.click(screen.getByTestId("grant-save"));

    await waitFor(() => expect(body).not.toBeNull());
    // Always sent explicitly — the backend rejects an omitted list rather than
    // defaulting it, so "all agents" has to be said out loud.
    expect(
      (body as unknown as { allowedAgents: string[] }).allowedAgents,
    ).toEqual(["*"]);
  });

  it("refuses to save an empty specific list, because empty means everyone", async () => {
    const user = userEvent.setup();
    renderSecrets();
    await openEditor(user, "google-gemini-key");

    await user.click(screen.getByTestId("grant-agent-remove-agent5"));
    await user.click(screen.getByTestId("grant-agent-remove-agent7"));

    expect(screen.getByTestId("grant-empty-error")).toBeInTheDocument();
    expect(screen.getByTestId("grant-save")).toBeDisabled();
  });

  it("warns about deployed agents that would lose access, and blocks save until acknowledged", async () => {
    const user = userEvent.setup();
    server.use(
      http.put(
        "*/secretstore/secrets/:tenantId/:keyName/grant",
        async ({ request, params }) => {
          const body = (await request.json()) as { allowedAgents: string[] };
          return HttpResponse.json({
            reference: "${vault:google-gemini-key}",
            tenantId: params.tenantId,
            keyName: params.keyName,
            dryRun: new URL(request.url).searchParams.get("dryRun") === "true",
            allowedAgents: body.allowedAgents,
            previousAllowedAgents: ["agent5", "agent7"],
            grantsAllAgents: false,
            description: null,
            createdAt: new Date().toISOString(),
            lastRotatedAt: null,
            agentsLosingAccess: [
              { agentId: "agent7", agentVersion: 3, environment: "production" },
            ],
            warning: "1 deployed agent(s) reference this secret…",
          });
        },
      ),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");

    const warning = await screen.findByTestId("grant-losing-access-warning");
    expect(
      within(warning).getByTestId("grant-losing-agent7"),
    ).toBeInTheDocument();
    // Not silently applied: an unacknowledged warning holds the save.
    expect(screen.getByTestId("grant-save")).toBeDisabled();

    await user.click(screen.getByTestId("grant-acknowledge"));
    expect(screen.getByTestId("grant-save")).not.toBeDisabled();
  });

  it("does not ask the backend about impact for a wildcard grant", async () => {
    const user = userEvent.setup();
    let calls = 0;
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName/grant", async () => {
        calls += 1;
        return HttpResponse.json(
          { error: "should not be called" },
          { status: 500 },
        );
      }),
    );

    renderSecrets();
    await openEditor(user, "openai-api-key");

    // Nothing can lose access to a secret every agent may use, so the round trip
    // would only cost latency.
    await waitFor(() =>
      expect(screen.getByTestId("grant-mode-all")).toHaveAttribute(
        "aria-checked",
        "true",
      ),
    );
    expect(calls).toBe(0);
  });

  /** A grant handler: dry runs answer via `dryRun`, real writes are counted. */
  function grantHandler(opts: {
    dryRun: () => Promise<Response> | Response;
    onWrite?: (body: Record<string, unknown>) => void;
  }) {
    return http.put(
      "*/secretstore/secrets/:tenantId/:keyName/grant",
      async ({ request, params }) => {
        if (new URL(request.url).searchParams.get("dryRun") === "true") {
          return opts.dryRun();
        }
        const body = (await request.json()) as Record<string, unknown>;
        opts.onWrite?.(body);
        return HttpResponse.json({
          reference: "${vault:google-gemini-key}",
          tenantId: params.tenantId,
          keyName: params.keyName,
          dryRun: false,
          allowedAgents: body.allowedAgents,
          previousAllowedAgents: ["agent5", "agent7"],
          grantsAllAgents: false,
          description: null,
          createdAt: new Date().toISOString(),
          lastRotatedAt: null,
          agentsLosingAccess: [],
        });
      },
    );
  }

  const noImpact = () =>
    HttpResponse.json({
      dryRun: true,
      allowedAgents: ["agent5"],
      agentsLosingAccess: [],
    });

  it("does not let a narrowing be saved while the impact check is still running", async () => {
    const user = userEvent.setup();
    let writes = 0;
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      grantHandler({
        dryRun: async () => {
          await gate;
          return noImpact();
        },
        onWrite: () => (writes += 1),
      }),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.click(screen.getByTestId("grant-agent-remove-agent7"));

    // Unknown is not "nothing breaks": Save waits for the answer.
    expect(
      await screen.findByTestId("grant-checking-impact"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("grant-save")).toBeDisabled();
    await user.click(screen.getByTestId("grant-save"));
    expect(writes).toBe(0);

    release();
    await waitFor(() =>
      expect(screen.getByTestId("grant-save")).not.toBeDisabled(),
    );
  });

  it("treats a failed impact check as a warning that must be acknowledged", async () => {
    const user = userEvent.setup();
    let writes = 0;
    server.use(
      grantHandler({
        dryRun: () => HttpResponse.json({ error: "boom" }, { status: 500 }),
        onWrite: () => (writes += 1),
      }),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.click(screen.getByTestId("grant-agent-remove-agent7"));

    expect(
      await screen.findByTestId("grant-losing-access-warning"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("grant-save")).toBeDisabled();
    await user.click(screen.getByTestId("grant-save"));
    expect(writes).toBe(0);

    await user.click(screen.getByTestId("grant-acknowledge"));
    await user.click(screen.getByTestId("grant-save"));
    await waitFor(() => expect(writes).toBe(1));
  });

  it("an acknowledgement does not carry over to a different impact", async () => {
    const user = userEvent.setup();
    // The answer depends on the list asked about, as the real dry run's does.
    server.use(
      http.put(
        "*/secretstore/secrets/:tenantId/:keyName/grant",
        async ({ request }) => {
          const { allowedAgents } = (await request.json()) as {
            allowedAgents: string[];
          };
          const losing = allowedAgents.includes("agent9")
            ? ["agent7", "agent8"]
            : ["agent7"];
          return HttpResponse.json({
            dryRun: true,
            agentsLosingAccess: losing.map((agentId) => ({
              agentId,
              agentVersion: 1,
              environment: "production",
            })),
          });
        },
      ),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.click(screen.getByTestId("grant-agent-remove-agent7"));
    await screen.findByTestId("grant-losing-agent7");
    await user.click(screen.getByTestId("grant-acknowledge"));
    expect(screen.getByTestId("grant-save")).not.toBeDisabled();

    // Adding an agent changes the list, and this time the answer is worse. The
    // tick was given for the first answer, not this one.
    await user.type(
      screen.getByPlaceholderText("Add an agent"),
      "agent9{Enter}",
    );
    await screen.findByTestId("grant-losing-agent8");
    expect(screen.getByTestId("grant-acknowledge")).not.toBeChecked();
    expect(screen.getByTestId("grant-save")).toBeDisabled();
  });

  it("leaves an unchanged description out of the request", async () => {
    const user = userEvent.setup();
    let body: Record<string, unknown> | null = null;
    server.use(grantHandler({ dryRun: noImpact, onWrite: (b) => (body = b) }));

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await waitFor(() =>
      expect(screen.getByTestId("grant-save")).not.toBeDisabled(),
    );
    await user.click(screen.getByTestId("grant-save"));

    await waitFor(() => expect(body).not.toBeNull());
    // Always sending it rewrote a missing description as "".
    expect(body).not.toHaveProperty("description");
  });

  it("sends an empty description when the operator cleared it", async () => {
    const user = userEvent.setup();
    let body: Record<string, unknown> | null = null;
    server.use(grantHandler({ dryRun: noImpact, onWrite: (b) => (body = b) }));

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.clear(screen.getByTestId("grant-description-input"));
    await waitFor(() =>
      expect(screen.getByTestId("grant-save")).not.toBeDisabled(),
    );
    await user.click(screen.getByTestId("grant-save"));

    await waitFor(() => expect(body).not.toBeNull());
    // "" is how a description is cleared; dropping it as falsy would keep the old one.
    expect(body).toHaveProperty("description", "");
  });

  it("moves between the two grant modes with the arrow, Home and End keys", async () => {
    const user = userEvent.setup();
    renderSecrets();
    await openEditor(user, "google-gemini-key");

    // AccessibleDialog moves focus to its first control in a requestAnimationFrame
    // after opening. Wait for that, or it can land after the focus() below.
    const dialog = screen.getByTestId("edit-grant-dialog");
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    screen.getByTestId("grant-mode-specific").focus();
    await user.keyboard("{Home}");
    expect(screen.getByTestId("grant-mode-all")).toHaveAttribute(
      "aria-checked",
      "true",
    );
    expect(screen.getByTestId("grant-mode-all")).toHaveFocus();
    await user.keyboard("{End}");
    expect(screen.getByTestId("grant-mode-specific")).toHaveAttribute(
      "aria-checked",
      "true",
    );
    await user.keyboard("{ArrowLeft}");
    expect(screen.getByTestId("grant-mode-all")).toHaveAttribute(
      "aria-checked",
      "true",
    );
  });

  it("sends the description when the operator changed it", async () => {
    const user = userEvent.setup();
    let body: Record<string, unknown> | null = null;
    server.use(grantHandler({ dryRun: noImpact, onWrite: (b) => (body = b) }));

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await user.clear(screen.getByTestId("grant-description-input"));
    await user.type(
      screen.getByTestId("grant-description-input"),
      "Rotated quarterly",
    );
    await waitFor(() =>
      expect(screen.getByTestId("grant-save")).not.toBeDisabled(),
    );
    await user.click(screen.getByTestId("grant-save"));

    await waitFor(() => expect(body).not.toBeNull());
    expect((body as unknown as { description: string }).description).toBe(
      "Rotated quarterly",
    );
  });

  it("surfaces a failed save as an error rather than a success", async () => {
    const user = userEvent.setup();
    let writes = 0;
    server.use(
      http.put(
        "*/secretstore/secrets/:tenantId/:keyName/grant",
        ({ request }) => {
          if (new URL(request.url).searchParams.get("dryRun") === "true") {
            return noImpact();
          }
          writes += 1;
          return HttpResponse.json(
            { error: "Secret not found" },
            { status: 404 },
          );
        },
      ),
    );

    renderSecrets();
    await openEditor(user, "google-gemini-key");
    await waitFor(() =>
      expect(screen.getByTestId("grant-save")).not.toBeDisabled(),
    );
    await user.click(screen.getByTestId("grant-save"));

    // The write went out and failed. Once the mutation has settled (Save is
    // usable again) the dialog is still open — a success would have closed it.
    await waitFor(() => expect(writes).toBe(1));
    await waitFor(() =>
      expect(screen.getByTestId("grant-save")).not.toBeDisabled(),
    );
    expect(screen.getByTestId("edit-grant-dialog")).toBeInTheDocument();
  });
});
