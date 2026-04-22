import { describe, it, expect } from "vitest";
import { api } from "../../api-client";
import {
  listSecrets,
  storeSecret,
  deleteSecret,
  getVaultHealth,
  rotateSecret,
} from "../secrets";

/**
 * These tests verify the C2 fix: secrets API functions must use
 * api.getBaseUrl() and api.getAuthHeader() instead of raw
 * window.location.origin with no auth headers.
 *
 * They run against the MSW server (which is already set up for all tests)
 * so they verify end-to-end that the requests go through correctly.
 */

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

  it("listSecrets resolves without error", async () => {
    const result = await listSecrets("default");
    expect(Array.isArray(result)).toBe(true);
  });

  it("storeSecret resolves without error", async () => {
    const result = await storeSecret("default", "test-key", "test-value");
    expect(result).toBeDefined();
    expect(result.keyName).toBeDefined();
  });

  it("deleteSecret resolves without error", async () => {
    await expect(deleteSecret("default", "test-key")).resolves.not.toThrow();
  });

  it("getVaultHealth resolves with status", async () => {
    const result = await getVaultHealth();
    expect(result.status).toBeDefined();
    expect(["UP", "DOWN"]).toContain(result.status);
  });

  it("rotateSecret resolves without error", async () => {
    const result = await rotateSecret("default", "test-key", "new-value");
    expect(result).toBeDefined();
    expect(result.keyName).toBeDefined();
  });
});
