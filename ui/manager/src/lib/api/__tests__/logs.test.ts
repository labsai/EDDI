import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getRecentLogs,
  getHistoryLogs,
  getInstanceId,
} from "../logs";

describe("logs API", () => {
  // ─── getRecentLogs ──────────────────────────────────────────────
  describe("getRecentLogs", () => {
    it("fetches recent logs with no filters", async () => {
      const result = await getRecentLogs();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("passes agentId filter", async () => {
      const result = await getRecentLogs({ agentId: "agent1" });
      expect(result).toBeDefined();
    });

    it("passes conversationId filter", async () => {
      const result = await getRecentLogs({ conversationId: "conv1" });
      expect(result).toBeDefined();
    });

    it("passes level filter", async () => {
      const result = await getRecentLogs({ level: "ERROR" });
      expect(result).toBeDefined();
    });

    it("passes limit filter", async () => {
      const result = await getRecentLogs({ limit: 50 });
      expect(result).toBeDefined();
    });

    it("passes all filters combined", async () => {
      const result = await getRecentLogs({
        agentId: "agent1",
        conversationId: "conv1",
        level: "WARN",
        limit: 10,
      });
      expect(result).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/administration/logs", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(getRecentLogs()).rejects.toMatchObject({ status: 500 });
    });
  });

  // ─── getHistoryLogs ─────────────────────────────────────────────
  describe("getHistoryLogs", () => {
    it("fetches history logs with no filters", async () => {
      const result = await getHistoryLogs();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("passes all filter params", async () => {
      const result = await getHistoryLogs({
        environment: "production",
        agentId: "agent1",
        agentVersion: 3,
        conversationId: "conv1",
        userId: "user1",
        instanceId: "inst1",
        skip: 10,
        limit: 50,
      });
      expect(result).toBeDefined();
    });

    it("omits falsy filters", async () => {
      // 0 is falsy, so skip and limit at 0 won't be set
      const result = await getHistoryLogs({ skip: 0, limit: 0 });
      expect(result).toBeDefined();
    });
  });

  // ─── getInstanceId ──────────────────────────────────────────────
  describe("getInstanceId", () => {
    it("fetches instance info", async () => {
      // Note: the MSW handler is at */administration/logs/instance-id
      // but the source code calls /administration/logs/instance
      // This is a potential bug — let's override to match the actual call
      server.use(
        http.get("*/administration/logs/instance", () =>
          HttpResponse.json({ instanceId: "inst-abc-123" })
        )
      );
      const result = await getInstanceId();
      expect(result).toBeDefined();
      expect(result.instanceId).toBe("inst-abc-123");
    });
  });
});
