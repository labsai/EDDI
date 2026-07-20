import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretsPage } from "@/pages/secrets";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { rotateDek, rotateKek, resetTenant } from "@/lib/api/secrets";

function renderSecrets() {
  return renderWithProviders(<SecretsPage />, {
    initialRoute: "/manage/secrets",
  });
}

describe("secrets key-lifecycle API functions", () => {
  it("rotateDek() POSTs to /{tenantId}/rotate-dek and returns re-encrypted count", async () => {
    let hit: { method: string; url: string } | null = null;
    server.use(
      http.post(
        "*/secretstore/secrets/:tenantId/rotate-dek",
        ({ request, params }) => {
          hit = { method: request.method, url: request.url };
          return HttpResponse.json({
            tenantId: params.tenantId,
            secretsReEncrypted: 3,
            message: "DEK rotated successfully. 3 secrets re-encrypted.",
          });
        },
      ),
    );

    const res = await rotateDek("default");
    expect(hit).not.toBeNull();
    expect(hit!.method).toBe("POST");
    expect(hit!.url).toContain("/secretstore/secrets/default/rotate-dek");
    expect(res.secretsReEncrypted).toBe(3);
  });

  it("rotateKek() POSTs to /admin/rotate-kek with backend field names", async () => {
    let body: Record<string, unknown> | null = null;
    let url = "";
    server.use(
      http.post("*/secretstore/secrets/admin/rotate-kek", async ({ request }) => {
        url = request.url;
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          deksReEncrypted: 2,
          message:
            "KEK rotated successfully. 2 DEKs re-encrypted. IMPORTANT: Update the EDDI_VAULT_MASTER_KEY environment variable to the new key and restart.",
        });
      }),
    );

    const res = await rotateKek({ oldKey: "old-secret", newKey: "new-secret-key" });
    expect(url).toContain("/secretstore/secrets/admin/rotate-kek");
    // The Manager's {oldKey,newKey} must be mapped to the backend's field names.
    expect(body).toEqual({
      oldMasterKey: "old-secret",
      newMasterKey: "new-secret-key",
    });
    expect(res.deksReEncrypted).toBe(2);
  });

  it("resetTenant() POSTs to /{tenantId}/reset and returns deleted count", async () => {
    let hit: { method: string; url: string } | null = null;
    server.use(
      http.post(
        "*/secretstore/secrets/:tenantId/reset",
        ({ request, params }) => {
          hit = { method: request.method, url: request.url };
          return HttpResponse.json({
            tenantId: params.tenantId,
            secretsDeleted: 5,
            message: "Vault reset.",
          });
        },
      ),
    );

    const res = await resetTenant("my-org");
    expect(hit).not.toBeNull();
    expect(hit!.method).toBe("POST");
    expect(hit!.url).toContain("/secretstore/secrets/my-org/reset");
    expect(res.secretsDeleted).toBe(5);
  });

  it("rotateDek() surfaces a backend error message", async () => {
    server.use(
      http.post("*/secretstore/secrets/:tenantId/rotate-dek", () =>
        HttpResponse.json({ error: "DEK rotation failed: boom" }, { status: 500 }),
      ),
    );
    await expect(rotateDek("default")).rejects.toThrow(/DEK rotation failed/);
  });
});

describe("SecretsPage — key lifecycle danger zone", () => {
  it("renders the danger zone with the three key-lifecycle actions", async () => {
    renderSecrets();
    expect(
      await screen.findByTestId("key-lifecycle-danger-zone"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("open-rotate-dek")).toBeInTheDocument();
    expect(screen.getByTestId("open-rotate-kek")).toBeInTheDocument();
    expect(screen.getByTestId("open-reset-tenant")).toBeInTheDocument();
  });

  it("distinguishes lifecycle actions from the per-secret value rotation", async () => {
    renderSecrets();
    // Per-tenant DEK rotation is titled distinctly from the per-secret "Rotate"
    expect(
      await screen.findByText("Rotate encryption key (DEK)"),
    ).toBeInTheDocument();
    expect(screen.getByText("Rotate master key (KEK)")).toBeInTheDocument();
    expect(screen.getByText("Reset vault for this tenant")).toBeInTheDocument();
  });

  // ─── Rotate DEK: safe, one-click-with-confirm ──────────────────────────

  it("rotate DEK is a guided one-click action behind a single confirm", async () => {
    let called = false;
    server.use(
      http.post("*/secretstore/secrets/:tenantId/rotate-dek", () => {
        called = true;
        return HttpResponse.json({
          tenantId: "default",
          secretsReEncrypted: 2,
          message: "DEK rotated successfully. 2 secrets re-encrypted.",
        });
      }),
    );

    renderSecrets();
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("open-rotate-dek"));

    // Confirmation makes clear it is safe / no restart.
    expect(screen.getByText(/no restart is required/i)).toBeInTheDocument();

    // A single confirm click performs the rotation (no extra inputs required).
    await user.click(screen.getByRole("button", { name: "Rotate DEK now" }));

    await waitFor(() => expect(called).toBe(true));
    // Dialog closes on success.
    await waitFor(() => {
      expect(
        screen.queryByRole("button", { name: "Rotate DEK now" }),
      ).not.toBeInTheDocument();
    });
  });

  // ─── Rotate KEK: min-length validation + restart instruction ───────────

  it("KEK confirm stays disabled until old key present and new key >= 8 chars", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("open-rotate-kek"));

    const confirm = screen.getByRole("button", {
      name: "Rotate master key now",
    });
    expect(confirm).toBeDisabled();

    // Old key present but new key too short → still disabled + error shown.
    await user.type(screen.getByTestId("kek-old-key-input"), "old-master-key");
    await user.type(screen.getByTestId("kek-new-key-input"), "short");
    expect(confirm).toBeDisabled();
    expect(screen.getByTestId("kek-new-key-error")).toBeInTheDocument();

    // Extend the new key past the 8-char minimum → enabled.
    await user.type(screen.getByTestId("kek-new-key-input"), "-enough");
    expect(confirm).not.toBeDisabled();
  });

  it("KEK dialog warns that keys travel in the request body (TLS)", async () => {
    renderSecrets();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("open-rotate-kek"));
    expect(screen.getByText(/TLS/i)).toBeInTheDocument();
    expect(screen.getByText(/request body/i)).toBeInTheDocument();
  });

  it("successful KEK rotation surfaces the update-and-restart instruction", async () => {
    server.use(
      http.post("*/secretstore/secrets/admin/rotate-kek", () =>
        HttpResponse.json({
          deksReEncrypted: 4,
          message: "KEK rotated successfully.",
        }),
      ),
    );

    renderSecrets();
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("open-rotate-kek"));
    await user.type(screen.getByTestId("kek-old-key-input"), "old-master-key");
    await user.type(screen.getByTestId("kek-new-key-input"), "new-master-key-123");
    await user.click(screen.getByRole("button", { name: "Rotate master key now" }));

    // Unmissable post-rotation instruction to update env var + restart.
    const result = await screen.findByTestId("kek-rotation-result");
    expect(result).toHaveTextContent(/EDDI_VAULT_MASTER_KEY/);
    expect(result).toHaveTextContent(/restart/i);
    expect(result).toHaveTextContent(/4/);
  });

  // ─── Reset: type-to-confirm gating ─────────────────────────────────────

  it("reset is gated by type-to-confirm — button disabled until tenant name typed", async () => {
    renderSecrets();
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("open-reset-tenant"));

    const confirm = screen.getByRole("button", {
      name: "Reset vault permanently",
    });
    expect(confirm).toBeDisabled();

    // Shows the secret count that will be lost.
    expect(screen.getByTestId("reset-secret-count")).toHaveTextContent(
      /permanently lost/i,
    );

    // Wrong text keeps it disabled.
    const input = screen.getByTestId("reset-confirm-input");
    await user.type(input, "wrong");
    expect(confirm).toBeDisabled();

    // Typing the exact tenant name enables it.
    await user.clear(input);
    await user.type(input, "default");
    expect(confirm).not.toBeDisabled();
  });

  it("confirmed reset calls the reset endpoint and closes the dialog", async () => {
    let called = false;
    server.use(
      http.post("*/secretstore/secrets/:tenantId/reset", ({ params }) => {
        called = true;
        return HttpResponse.json({
          tenantId: params.tenantId,
          secretsDeleted: 2,
          message: "Vault reset.",
        });
      }),
    );

    renderSecrets();
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("open-reset-tenant"));
    await user.type(screen.getByTestId("reset-confirm-input"), "default");
    await user.click(screen.getByRole("button", { name: "Reset vault permanently" }));

    await waitFor(() => expect(called).toBe(true));
    await waitFor(() => {
      expect(
        screen.queryByRole("button", { name: "Reset vault permanently" }),
      ).not.toBeInTheDocument();
    });
  });
});
