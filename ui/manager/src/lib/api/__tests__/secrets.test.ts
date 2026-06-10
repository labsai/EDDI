import { describe, it, expect, afterEach } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { api } from "../../api-client";
import {
  listSecrets,
  storeSecret,
  deleteSecret,
  getVaultHealth,
  rotateSecret,
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

  it("returns empty array on non-ok response", async () => {
    server.use(
      http.get("*/secretstore/secrets/:tenantId", () =>
        new HttpResponse(null, { status: 500 })
      )
    );
    const result = await listSecrets("default");
    expect(result).toEqual([]);
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

  it("throws generic message when error body cannot be parsed", async () => {
    server.use(
      http.put("*/secretstore/secrets/:tenantId/:keyName", () =>
        new HttpResponse("not json", {
          status: 400,
          headers: { "Content-Type": "text/plain" },
        })
      )
    );
    await expect(
      storeSecret("default", "test-key", "val")
    ).rejects.toThrow(/Unknown error/);
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
  it("rotates a secret successfully", async () => {
    const result = await rotateSecret("default", "test-key", "new-value");
    expect(result).toBeDefined();
    expect(result.keyName).toBeDefined();
  });

  it("includes description when provided", async () => {
    let capturedBody: Record<string, unknown> = {};
    server.use(
      http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({
          reference: "vault:default/test-key",
          tenantId: "default",
          keyName: "test-key",
        });
      })
    );
    await rotateSecret("default", "test-key", "new-val", "rotated key");
    expect(capturedBody.description).toBe("rotated key");
  });

  it("throws on 503 with vault not configured message", async () => {
    server.use(
      http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", () =>
        new HttpResponse(null, { status: 503 })
      )
    );
    await expect(
      rotateSecret("default", "test-key", "new-value")
    ).rejects.toThrow("Secrets vault is not configured");
  });

  it("falls back to storeSecret on 404", async () => {
    server.use(
      http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", () =>
        new HttpResponse(null, { status: 404 })
      )
    );
    // Should fall back to PUT (storeSecret) which is handled by default MSW
    const result = await rotateSecret("default", "test-key", "new-value");
    expect(result).toBeDefined();
    expect(result.keyName).toBeDefined();
  });

  it("falls back to storeSecret on 405", async () => {
    server.use(
      http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", () =>
        new HttpResponse(null, { status: 405 })
      )
    );
    const result = await rotateSecret("default", "test-key", "new-value");
    expect(result).toBeDefined();
  });

  it("throws on other non-ok status", async () => {
    server.use(
      http.post("*/secretstore/secrets/:tenantId/:keyName/rotate", () =>
        HttpResponse.json({ error: "Rotation limit exceeded" }, { status: 429 })
      )
    );
    await expect(
      rotateSecret("default", "test-key", "new-value")
    ).rejects.toThrow("Rotation limit exceeded");
  });
});
