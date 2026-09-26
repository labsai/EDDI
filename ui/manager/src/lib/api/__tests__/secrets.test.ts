import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { api } from "../../api-client";
import {
  listSecrets,
  storeSecret,
  deleteSecret,
  getVaultHealth,
  rotateSecret,
  findSecret,
} from "../secrets";

describe("secrets API — uses ApiClient (C2 fix)", () => {
  it("api.getBaseUrl() returns a valid URL", () => {
    const baseUrl = api.getBaseUrl();
    expect(typeof baseUrl).toBe("string");
    expect(baseUrl.length).toBeGreaterThan(0);
  });

  it("api.getAuthHeader() returns an object", () => {
    const headers = api.getAuthHeader();
    expect(typeof headers).toBe("object");
  });
});

describe("listSecrets", () => {
  it("returns array of secrets on success", async () => {
    const result = await listSecrets("default");
    expect(Array.isArray(result)).toBe(true);
  });

  it("throws on non-ok response (does not mask errors as an empty vault)", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () =>
        HttpResponse.json({ error: "Boom" }, { status: 500 })
      )
    );
    await expect(listSecrets("default")).rejects.toThrow("Boom");
  });

  it("throws the vault-not-configured message on 503", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () =>
        new HttpResponse(null, { status: 503 })
      )
    );
    await expect(listSecrets("default")).rejects.toThrow(
      "Secrets vault is not configured",
    );
  });
});

describe("storeSecret", () => {
  it("stores a secret and returns response with keyName", async () => {
    const result = await storeSecret("default", "test-key", "test-value");
    expect(result).toBeDefined();
    expect(result.keyName).toBeDefined();
  });

  it("includes description when provided", async () => {
    let capturedBody: Record<string, unknown> = {};
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          reference: "vault:default/desc-key",
          tenantId: "default",
          keyName: "desc-key",
        });
      })
    );
    await storeSecret("default", "desc-key", "val", "my description");
    expect(capturedBody.description).toBe("my description");
  });

  it("includes allowedAgents when provided", async () => {
    let capturedBody: Record<string, unknown> = {};
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          reference: "vault:default/agents-key",
          tenantId: "default",
          keyName: "agents-key",
        });
      })
    );
    await storeSecret("default", "agents-key", "val", undefined, ["agent1", "agent2"]);
    expect(capturedBody.allowedAgents).toEqual(["agent1", "agent2"]);
  });

  it("throws on 503 with vault not configured message", async () => {
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () =>
        new HttpResponse(null, { status: 503 })
      )
    );
    await expect(
      storeSecret("default", "test-key", "val")
    ).rejects.toThrow("Secrets vault is not configured");
  });

  it("throws on other non-ok status with error from body", async () => {
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () =>
        HttpResponse.json({ error: "Bad request" }, { status: 400 })
      )
    );
    await expect(
      storeSecret("default", "test-key", "val")
    ).rejects.toThrow("Bad request");
  });

  it("names the operation and status when the error body cannot be parsed", async () => {
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () =>
        new HttpResponse("not json", {
          status: 400,
          headers: { "Content-Type": "text/plain" },
        })
      )
    );
    // "Unknown error" told the user nothing. When the body is unreadable the
    // only real information is which operation failed and with what status, so
    // that is what the shared `throwVaultError` helper reports.
    await expect(
      storeSecret("default", "test-key", "val")
    ).rejects.toThrow(/Failed to store secret \(HTTP 400\)/);
  });
});

describe("deleteSecret", () => {
  it("deletes a secret without error", async () => {
    await expect(deleteSecret("default", "test-key")).resolves.not.toThrow();
  });

  it("throws on 503 with vault not configured message", async () => {
    server.use(
      http.delete("*/secretstore/secrets/:tenantId/:keyName", () =>
        new HttpResponse(null, { status: 503 })
      )
    );
    await expect(
      deleteSecret("default", "test-key")
    ).rejects.toThrow("Secrets vault is not configured");
  });

  it("throws on other non-ok status", async () => {
    server.use(
      http.delete("*/secretstore/secrets/:tenantId/:keyName", () =>
        HttpResponse.json({ error: "Not found" }, { status: 404 })
      )
    );
    await expect(
      deleteSecret("default", "test-key")
    ).rejects.toThrow("Not found");
  });

  it("succeeds on 204 No Content", async () => {
    server.use(
      http.delete("*/secretstore/secrets/:tenantId/:keyName", () =>
        new HttpResponse(null, { status: 204 })
      )
    );
    await expect(deleteSecret("default", "test-key")).resolves.not.toThrow();
  });
});

describe("getVaultHealth", () => {
  it("returns UP status on healthy vault", async () => {
    const result = await getVaultHealth();
    expect(result.status).toBeDefined();
    expect(["UP", "DOWN"]).toContain(result.status);
  });

  it("returns DOWN status on 503 with extended error info", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.json(
          {
            provider: "hashicorp-vault",
            error: "Vault sealed",
            reason: "Auto-seal triggered",
            action: "Unseal the vault",
            docs: "https://docs.example.com",
          },
          { status: 503 }
        )
      )
    );
    const result = await getVaultHealth();
    expect(result.status).toBe("DOWN");
    expect(result.available).toBe(false);
    expect(result.provider).toBe("hashicorp-vault");
    expect(result.error).toBe("Vault sealed");
    expect(result.reason).toBe("Auto-seal triggered");
    expect(result.action).toBe("Unseal the vault");
    expect(result.docs).toBe("https://docs.example.com");
  });

  it("returns DOWN when fetch throws network error", async () => {
    server.use(
      http.get("*/secretstore/secrets/health", () =>
        HttpResponse.error()
      )
    );
    const result = await getVaultHealth();
    expect(result.status).toBe("DOWN");
    expect(result.provider).toBe("unknown");
    expect(result.available).toBe(false);
  });
});

describe("rotateSecret", () => {
  /** Capture the one PUT a rotation makes, and fail on any POST to the old path. */
  function captureStore() {
    const hits: { url: string; body: Record<string, unknown> }[] = [];
    const rotatePosts: string[] = [];
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", async ({ request, params }) => {
        hits.push({ url: request.url, body: (await request.json()) as Record<string, unknown> });
        return HttpResponse.json({
          reference: `\${vault:${params.keyName as string}}`,
          tenantId: params.tenantId,
          keyName: params.keyName,
        });
      }),
      http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", ({ request }) => {
        rotatePosts.push(request.url);
        return HttpResponse.json({}, { status: 404 });
      }),
    );
    return { hits, rotatePosts };
  }

  it("sends the current narrowed grant and description with the new value (S1)", async () => {
    // A PUT without allowedAgents is stored as ["*"] with a blank description by
    // a backend before the vault-key-safety fix — which is what the old /rotate
    // 404 fallback sent on every rotation.
    const { hits, rotatePosts } = captureStore();
    await rotateSecret("default", "gemini-key", "new-value", {
      allowedAgents: ["agent5", "agent7"],
      description: "Gemini key",
    });
    expect(rotatePosts).toEqual([]);
    expect(hits).toHaveLength(1);
    expect(hits[0]!.body).toEqual({
      value: "new-value",
      allowedAgents: ["agent5", "agent7"],
      description: "Gemini key",
    });
  });

  it("sends the explicit wildcard for a secret open to every agent", async () => {
    const { hits } = captureStore();
    await rotateSecret("default", "k", "v", { allowedAgents: [], description: null });
    expect(hits[0]!.body).toEqual({ value: "v", allowedAgents: ["*"] });
  });

  it("surfaces a store failure instead of reporting a rotation", async () => {
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () =>
        HttpResponse.json({ error: "Rotation refused" }, { status: 500 }),
      ),
    );
    await expect(
      rotateSecret("default", "k", "v", { allowedAgents: ["*"], description: null }),
    ).rejects.toThrow("Rotation refused");
  });
});

describe("findSecret", () => {
  it("returns the key's metadata from a fresh listing, or null", async () => {
    expect((await findSecret("default", "google-gemini-key"))?.allowedAgents).toEqual([
      "agent5",
      "agent7",
    ]);
    expect(await findSecret("default", "no-such-key")).toBeNull();
  });

  it("looks the key up by its own path, and raises anything but a 404", async () => {
    let url = "";
    server.use(
      http.get("*/secretstore/secrets/:tenantId/:keyName", ({ request }) => {
        url = request.url;
        return HttpResponse.json({ error: "Failed to get metadata" }, { status: 500 });
      }),
    );
    // "Could not check" must never read as "free to create".
    await expect(findSecret("team a", "k/1")).rejects.toThrow("Failed to get metadata");
    expect(new URL(url).pathname).toBe("/secretstore/secrets/team%20a/k%2F1");
  });
});

describe("path segments are encoded", () => {
  it("does not let a key name address another resource", async () => {
    const urls: string[] = [];
    server.use(
      http.put("*/secretstore/secrets/*", ({ request }) => {
        urls.push(request.url);
        return HttpResponse.json({ reference: "x", tenantId: "t", keyName: "k" });
      }),
      http.delete("*/secretstore/secrets/*", ({ request }) => {
        urls.push(request.url);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await storeSecret("team a", "a/b?c", "v");
    await deleteSecret("team a", "a/b?c");
    for (const url of urls) {
      expect(new URL(url).pathname).toBe("/secretstore/secrets/team%20a/a%2Fb%3Fc");
      expect(new URL(url).search).toBe("");
    }
    expect(urls).toHaveLength(2);
  });
});
