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
