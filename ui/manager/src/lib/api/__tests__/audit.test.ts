import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getAuditTrail,
  getAuditTrailByAgent,
  getEntryCount,
} from "../audit";

describe("audit API", () => {
  // ─── getAuditTrail ─────────────────────────────────────────────
  describe("getAuditTrail", () => {
    it("fetches audit trail for a conversation with defaults", async () => {
      const result = await getAuditTrail("conv1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBeGreaterThan(0);
      expect(result[0]).toHaveProperty("conversationId");
      expect(result[0]).toHaveProperty("taskType");
    });

    it("passes skip and limit parameters", async () => {
      const result = await getAuditTrail("conv1", 5, 10);
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/auditstore/:conversationId", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(getAuditTrail("conv-fail")).rejects.toMatchObject({
        status: 500,
      });
    });
  });

  // ─── getAuditTrailByAgent ──────────────────────────────────────
  describe("getAuditTrailByAgent", () => {
    it("fetches audit trail by agent ID", async () => {
      const result = await getAuditTrailByAgent("agent1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("passes agentVersion when provided", async () => {
      const result = await getAuditTrailByAgent("agent1", 3);
      expect(result).toBeDefined();
    });

    it("omits agentVersion when null", async () => {
      const result = await getAuditTrailByAgent("agent1", null);
      expect(result).toBeDefined();
    });

    it("passes custom skip and limit", async () => {
      const result = await getAuditTrailByAgent("agent1", undefined, 10, 50);
      expect(result).toBeDefined();
    });
  });

  // ─── getEntryCount ─────────────────────────────────────────────
  describe("getEntryCount", () => {
    it("returns the count of audit entries", async () => {
      const result = await getEntryCount("conv1");
      expect(result).toBeDefined();
      expect(typeof result).toBe("number");
    });
  });
});
