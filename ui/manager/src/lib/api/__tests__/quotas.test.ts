import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  listQuotas,
  getQuota,
  updateQuota,
  getUsage,
  resetUsage,
} from "../quotas";

describe("quotas API", () => {
  describe("listQuotas", () => {
    it("lists all tenant quotas", async () => {
      const result = await listQuotas();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/administration/quotas", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(listQuotas()).rejects.toMatchObject({ status: 500 });
    });
  });

  describe("getQuota", () => {
    it("fetches quota for a tenant", async () => {
      const result = await getQuota("tenant1");
      expect(result).toBeDefined();
    });
  });

  describe("updateQuota", () => {
    it("updates a tenant quota", async () => {
      const quota = {
        tenantId: "tenant1",
        maxConversationsPerDay: 100,
        maxAgentsPerTenant: 10,
        maxApiCallsPerMinute: 60,
        maxMonthlyCostUsd: 500,
        enabled: true,
      };
      const result = await updateQuota("tenant1", quota);
      expect(result).toBeDefined();
    });
  });

  describe("getUsage", () => {
    it("fetches usage for a tenant", async () => {
      const result = await getUsage("tenant1");
      expect(result).toBeDefined();
    });
  });

  describe("resetUsage", () => {
    it("resets usage counters for a tenant", async () => {
      await expect(resetUsage("tenant1")).resolves.toBeUndefined();
    });
  });
});
