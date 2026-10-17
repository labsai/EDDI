import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { WorkspacesPage } from "@/pages/workspaces";

const PERSONAL = { id: "user:alice@example.com", kind: "personal", label: "alice@example.com" };

function enableWorkspaces() {
  server.use(
    http.get("*/workspaces", () =>
      HttpResponse.json({
        enabled: true,
        principal: "alice@example.com",
        defaultSpace: PERSONAL.id,
        spaces: [PERSONAL],
        seesEverything: true,
      }),
    ),
  );
}

describe("WorkspacesPage — deleting a space variable", () => {
  beforeEach(() => {
    try {
      localStorage.clear();
    } catch {
      // No storage: nothing remembered to clear.
    }
  });

  // Regression: the trash button deleted the variable on the spot.
  it("asks before deleting, and deletes only on confirm", async () => {
    enableWorkspaces();
    const deleted: string[] = [];
    server.use(
      http.get("*/spacestore/variables", () =>
        HttpResponse.json([{ key: "model", value: "gpt", description: null, reference: "${vars:u.alice/model}" }]),
      ),
      http.delete("*/spacestore/variables/:key", ({ params }) => {
        deleted.push(String(params.key));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("space-variable-delete-model"));
    expect(await screen.findByTestId("alert-dialog-confirm")).toBeInTheDocument();
    expect(deleted).toEqual([]);

    await user.click(screen.getByTestId("alert-dialog-confirm"));
    await waitFor(() => expect(deleted).toEqual(["model"]));
  });
});

describe("WorkspacesPage — settings", () => {
  // Regression: every save sent legacyVisibility "shared" when nothing was
  // stored, turning the default into a stored choice nobody had made.
  it("leaves the legacy policy unset when the admin only changed the default space", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put("*/workspaces/settings", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          enforcing: false,
          groupsClaim: "groups",
          defaultSpace: { value: "engineering", source: "STORED", property: "eddi.workspaces.default-space" },
          legacyVisibility: { value: "shared", source: "DEFAULT", property: "eddi.workspaces.legacy-visibility" },
          warnings: [],
        });
      }),
    );
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();

    expect(await screen.findByTestId("legacy-default")).toBeChecked();
    await user.type(screen.getByTestId("default-space-input"), "engineering");
    await user.click(screen.getByTestId("workspace-settings-save"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.defaultSpace).toBe("engineering");
    expect(body!.legacyVisibility).toBeNull();
  });

  it("still sends an explicit choice", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.put("*/workspaces/settings", async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          enforcing: false,
          groupsClaim: "groups",
          defaultSpace: { value: null, source: "DEFAULT", property: "eddi.workspaces.default-space" },
          legacyVisibility: { value: "admin-only", source: "STORED", property: "eddi.workspaces.legacy-visibility" },
          warnings: [],
        });
      }),
    );
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("legacy-admin-only"));
    await user.click(screen.getByTestId("workspace-settings-save"));

    await waitFor(() => expect(body).not.toBeNull());
    expect(body!.legacyVisibility).toBe("admin-only");
  });
});

describe("WorkspacesPage — storing a space secret", () => {
  function captureSecretWrites(stored: Record<string, unknown>[] = []) {
    const writes: Record<string, unknown>[] = [];
    server.use(
      http.get("*/spacestore/secrets", () => HttpResponse.json(stored)),
      http.put("*/spacestore/secrets/:key", async ({ params, request }) => {
        writes.push((await request.json()) as Record<string, unknown>);
        return HttpResponse.json({ keyName: String(params.key), reference: "${vault:u.alice/k}" });
      }),
    );
    return writes;
  }

  async function fillNameAndValue(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByTestId("space-secret-name"), "openai-key");
    await user.type(screen.getByTestId("space-secret-value"), "sk-test");
  }

  it("is not submittable until a host or Any host is chosen", async () => {
    enableWorkspaces();
    const writes = captureSecretWrites();
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();
    await fillNameAndValue(user);

    expect(screen.getByTestId("space-secret-save")).toBeDisabled();
    expect(screen.getByTestId("space-secret-hosts-missing")).toBeInTheDocument();
    expect(writes).toEqual([]);
  });

  it("sends every agent and the hosts typed", async () => {
    enableWorkspaces();
    const writes = captureSecretWrites();
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();
    await fillNameAndValue(user);
    await user.type(screen.getByTestId("space-secret-hosts-input-input"), "api.openai.com");
    await user.click(screen.getByTestId("space-secret-save"));

    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({
      value: "sk-test",
      allowedAgents: ["*"],
      allowedHosts: ["api.openai.com"],
    });
  });

  it("sends [\"*\"] as allowedHosts for Any host", async () => {
    enableWorkspaces();
    const writes = captureSecretWrites();
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();
    await fillNameAndValue(user);
    await user.click(screen.getByTestId("space-secret-hosts-mode-any"));
    await user.click(screen.getByTestId("space-secret-save"));

    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({ allowedAgents: ["*"], allowedHosts: ["*"] });
  });

  it("re-saving an existing key sends no grant, so a narrowed one is kept", async () => {
    enableWorkspaces();
    const writes = captureSecretWrites([
      { keyName: "openai-key", reference: "${vault:u.alice/openai-key}", allowedAgents: ["agent1"], allowedHosts: ["api.openai.com"] },
    ]);
    renderPage("/manage/workspaces", <WorkspacesPage />);
    const user = userEvent.setup();
    await screen.findByText("openai-key");
    await fillNameAndValue(user);
    await user.type(screen.getByTestId("space-secret-hosts-input-input"), "api.openai.com");
    await user.click(screen.getByTestId("space-secret-save"));

    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).not.toHaveProperty("allowedAgents");
    expect(writes[0]).toMatchObject({ value: "sk-test", allowedHosts: ["api.openai.com"] });
  });

  it("shows a stored secret's destinations", async () => {
    enableWorkspaces();
    server.use(
      http.get("*/spacestore/secrets", () =>
        HttpResponse.json([
          { keyName: "narrow", reference: "${vault:u.alice/narrow}", allowedHosts: ["api.openai.com"] },
          { keyName: "open", reference: "${vault:u.alice/open}", allowedHosts: [] },
        ]),
      ),
    );
    renderPage("/manage/workspaces", <WorkspacesPage />);

    expect(await screen.findByTestId("space-secret-hosts-narrow")).toHaveTextContent("api.openai.com");
    expect(screen.queryByTestId("space-secret-hosts-open")).not.toBeInTheDocument();
  });
});
