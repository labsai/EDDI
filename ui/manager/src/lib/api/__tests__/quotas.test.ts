import { describe, it, expect } from "vitest";
import {
  getQuota,
  updateQuota,
  getUsage,
  resetUsage,
} from "../quotas";

describe("quotas API", () => {

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
