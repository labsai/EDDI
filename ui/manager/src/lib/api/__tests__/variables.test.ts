import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  listVariables,
  getVariable,
  upsertVariable,
  deleteVariable,
  isValidVariableKey,
} from "../variables";

describe("variables API", () => {
  // ─── listVariables ──────────────────────────────────────────────
  describe("listVariables", () => {
    it("lists variables with default tenant", async () => {
      const result = await listVariables();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("lists variables for custom tenant", async () => {
      const result = await listVariables("custom-tenant");
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/variablestore/variables/:tenantId", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(listVariables()).rejects.toMatchObject({ status: 500 });
    });
  });

  // ─── getVariable ────────────────────────────────────────────────
  describe("getVariable", () => {
    it("gets a variable by key", async () => {
      const result = await getVariable("default-model");
      expect(result).toBeDefined();
      expect(result.key).toBe("default-model");
    });

    it("gets a variable with custom tenant", async () => {
      // Override handler to accept any tenant/key combo
      server.use(
        http.get("*/variablestore/variables/:tenantId/:key", () =>
          HttpResponse.json({ key: "test-key", value: "test-val" })
        )
      );
      const result = await getVariable("test-key", "custom-tenant");
      expect(result).toBeDefined();
    });

    it("handles 404 for unknown key", async () => {
      await expect(getVariable("nonexistent-key")).rejects.toMatchObject({
        status: 404,
      });
    });
  });

  // ─── upsertVariable ────────────────────────────────────────────
  describe("upsertVariable", () => {
    it("creates or updates a variable", async () => {
      await expect(
        upsertVariable("MY_VAR", {
          key: "MY_VAR",
          value: "my_value",
          description: "A test variable",
        })
      ).resolves.toBeUndefined();
    });

    it("works with custom tenant", async () => {
      await expect(
        upsertVariable(
          "MY_VAR",
          { key: "MY_VAR", value: "val" },
          "custom-tenant"
        )
      ).resolves.toBeUndefined();
    });
  });

  // ─── deleteVariable ─────────────────────────────────────────────
  describe("deleteVariable", () => {
    it("deletes a variable by key", async () => {
      await expect(deleteVariable("MY_VAR")).resolves.toBeUndefined();
    });

    it("deletes with custom tenant", async () => {
      await expect(
        deleteVariable("MY_VAR", "custom-tenant")
      ).resolves.toBeUndefined();
    });
  });

  // ─── isValidVariableKey ─────────────────────────────────────────
  describe("isValidVariableKey", () => {
    it("accepts alphanumeric keys", () => {
      expect(isValidVariableKey("myKey123")).toBe(true);
    });

    it("accepts keys with dots, underscores, and hyphens", () => {
      expect(isValidVariableKey("my.key_name-1")).toBe(true);
    });

    it("rejects empty string", () => {
      expect(isValidVariableKey("")).toBe(false);
    });

    it("rejects keys with spaces", () => {
      expect(isValidVariableKey("my key")).toBe(false);
    });

    it("rejects keys with special characters", () => {
      expect(isValidVariableKey("key@#$")).toBe(false);
    });

    it("rejects keys with slashes", () => {
      expect(isValidVariableKey("path/key")).toBe(false);
    });
  });
});
