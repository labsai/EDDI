import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SecretsPage } from "@/pages/secrets";
import { server } from "@/test/mocks/server";

const toastMock = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
}));
vi.mock("sonner", () => ({ toast: toastMock }));

beforeEach(() => {
  for (const fn of Object.values(toastMock)) fn.mockClear();
});

/**
 * The secret-management paths that used to change more than the operator
 * asked for: "Add Secret" replacing an existing key, and Rotate resetting a
 * narrowed grant to every agent (UI review High 7 / S1).
 */

function renderSecrets() {
  return renderWithProviders(<SecretsPage />, { initialRoute: "/manage/secrets" });
}

/** Record every secret PUT (value writes, not grant edits). */
function recordStores() {
  const stores: { url: string; body: Record<string, unknown> }[] = [];
  server.use(
    http.put("*/secretstore/secrets/:tenantId/:keyName", async ({ request, params }) => {
      stores.push({ url: request.url, body: (await request.json()) as Record<string, unknown> });
      return HttpResponse.json(
        { reference: "r", tenantId: params.tenantId, keyName: params.keyName },
        { status: 201 },
      );
    }),
  );
  return stores;
}

describe("Add Secret never overwrites", () => {
  it("refuses an existing key name without writing, and offers Rotate", async () => {
    const stores = recordStores();
    renderSecrets();
    const user = userEvent.setup();
    await screen.findByText("google-gemini-key");

    await user.click(screen.getByTestId("create-secret-button"));
    await user.type(screen.getByTestId("new-key-input"), "google-gemini-key");
    await user.type(screen.getByTestId("new-value-input"), "replacement");
    await user.click(screen.getByTestId("confirm-create-button"));

    expect(await screen.findByTestId("secret-exists-error")).toHaveTextContent(
      /google-gemini-key.*already exists/,
    );
    expect(stores).toEqual([]);
    // The dialog stays open with what was typed.
    expect(screen.getByTestId("new-key-input")).toHaveValue("google-gemini-key");

    await user.click(screen.getByTestId("secret-exists-rotate"));
    expect(screen.queryByTestId("new-key-input")).not.toBeInTheDocument();
    expect(await screen.findByTestId("rotate-value-input")).toBeInTheDocument();
    expect(screen.getByTestId("rotate-keeps-grant")).toHaveTextContent("agent5, agent7");
  });

  it("clears the refusal once the name is edited", async () => {
    recordStores();
    renderSecrets();
    const user = userEvent.setup();
    await screen.findByText("openai-api-key");

    await user.click(screen.getByTestId("create-secret-button"));
    await user.type(screen.getByTestId("new-key-input"), "openai-api-key");
    await user.type(screen.getByTestId("new-value-input"), "v");
    await user.click(screen.getByTestId("confirm-create-button"));
    await screen.findByTestId("secret-exists-error");

    await user.type(screen.getByTestId("new-key-input"), "-2");
    expect(screen.queryByTestId("secret-exists-error")).not.toBeInTheDocument();
  });

  it("stores the value exactly as typed", async () => {
    const stores = recordStores();
    renderSecrets();
    const user = userEvent.setup();
    await screen.findByText("openai-api-key");

    await user.click(screen.getByTestId("create-secret-button"));
    await user.type(screen.getByTestId("new-key-input"), "  padded-key  ");
    await user.type(screen.getByTestId("new-value-input"), " pass phrase ");
    await user.click(screen.getByTestId("confirm-create-button"));

    await waitFor(() => expect(stores).toHaveLength(1));
    expect(new URL(stores[0]!.url).pathname).toBe("/secretstore/secrets/default/padded-key");
    expect(stores[0]!.body.value).toBe(" pass phrase ");
  });
});

describe("Rotate keeps the grant", () => {
  it("sends the secret's current narrowed grant and description with the new value", async () => {
    const stores = recordStores();
    renderSecrets();
    const user = userEvent.setup();
    await screen.findByText("google-gemini-key");

    await user.click(screen.getByTestId("rotate-google-gemini-key"));
    await user.type(screen.getByTestId("rotate-value-input"), "rotated-value");
    await user.click(screen.getByTestId("confirm-rotate-button"));

    await waitFor(() => expect(stores).toHaveLength(1));
    expect(stores[0]!.body).toEqual({
      value: "rotated-value",
      allowedAgents: ["agent5", "agent7"],
      description: "Google Gemini 2.5 Flash API key",
    });
    await waitFor(() =>
      expect(screen.queryByTestId("rotate-value-input")).not.toBeInTheDocument(),
    );
  });

  it("reports a key deleted in the meantime instead of re-creating it", async () => {
    const stores = recordStores();
    renderSecrets();
    const user = userEvent.setup();
    await screen.findByText("google-gemini-key");

    await user.click(screen.getByTestId("rotate-google-gemini-key"));
    // Deleted elsewhere after the list was shown.
    server.use(http.get("*/secretstore/secrets/:tenantId", () => HttpResponse.json([])));
    await user.type(screen.getByTestId("rotate-value-input"), "v");
    await user.click(screen.getByTestId("confirm-rotate-button"));

    await waitFor(() =>
      expect(toastMock.error).toHaveBeenCalledWith(expect.stringMatching(/no longer exists/)),
    );
    expect(stores).toEqual([]);
    expect(screen.getByTestId("rotate-value-input")).toBeInTheDocument();
  });
});

describe("tenant field", () => {
  it("lists the trimmed tenant", async () => {
    const tenants: string[] = [];
    server.use(
      http.get("*/secretstore/secrets/:tenantId", ({ params }) => {
        if (params.tenantId === "health") return;
        tenants.push(params.tenantId as string);
        return HttpResponse.json([]);
      }),
    );
    renderSecrets();
    const user = userEvent.setup();
    const input = screen.getByTestId("tenant-input");
    await user.clear(input);
    await user.type(input, " team-b ");

    await waitFor(() => expect(tenants).toContain("team-b"));
    expect(tenants.some((tenant) => tenant !== tenant.trim())).toBe(false);
  });
});

describe("adopt master key (lost-key recovery)", () => {
  it("requires the acknowledgement, then lists the tenants to reset", async () => {
    let calls = 0;
    server.use(
      http.post("*/secretstore/secrets/admin/adopt-master-key", () => {
        calls++;
        return HttpResponse.json({
          tenantsNeedingReset: ["team-b"],
          systemValuesReset: false,
          message: "ok",
        });
      }),
    );
    renderSecrets();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("open-adopt-key"));

    const dialog = await screen.findByRole("dialog");
    const confirm = within(dialog).getByRole("button", { name: "Adopt master key" });
    expect(confirm).toBeDisabled();
    await user.click(within(dialog).getByTestId("adopt-key-ack"));
    await user.click(confirm);

    const result = await screen.findByTestId("adopt-key-result");
    expect(calls).toBe(1);
    await user.click(within(result).getByTestId("adopt-reset-tenant-team-b"));
    expect(screen.getByTestId("tenant-input")).toHaveValue("team-b");
  });

  it("explains that an older backend needs no adopt step", async () => {
    server.use(
      http.post("*/secretstore/secrets/admin/adopt-master-key", () =>
        new HttpResponse(null, { status: 404 }),
      ),
    );
    renderSecrets();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("open-adopt-key"));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByTestId("adopt-key-ack"));
    await user.click(within(dialog).getByRole("button", { name: "Adopt master key" }));

    await waitFor(() =>
      expect(toastMock.info).toHaveBeenCalledWith(expect.stringMatching(/does not need one/)),
    );
    expect(screen.queryByTestId("adopt-key-result")).not.toBeInTheDocument();
  });
});
