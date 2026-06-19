import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getConversationCosts,
  getToolRateLimit,
  getCacheStats,
  getToolHistory,
  getToolCosts,
} from "../tool-metrics";

describe("tool-metrics API", () => {
  // ─── getConversationCosts ───────────────────────────────────────
  describe("getConversationCosts", () => {
    it("fetches conversation costs", async () => {
      const result = await getConversationCosts("conv1");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("conversationId");
      expect(result).toHaveProperty("totalCost");
      expect(result).toHaveProperty("toolCallCount");
    });

    it("returns null for 404 (no cost data yet)", async () => {
      server.use(
        http.get("*/llm/tools/costs/conversation/:id", () =>
          HttpResponse.json({ message: "Not Found" }, { status: 404 })
        )
      );
      const result = await getConversationCosts("conv-no-data");
      expect(result).toBeNull();
    });

    it("throws for non-404 errors", async () => {
      server.use(
        http.get("*/llm/tools/costs/conversation/:id", () =>
          HttpResponse.json({ message: "Server Error" }, { status: 500 })
        )
      );
      await expect(
        getConversationCosts("conv-fail")
      ).rejects.toMatchObject({ status: 500 });
    });
  });

  // ─── getToolRateLimit ───────────────────────────────────────────
  describe("getToolRateLimit", () => {
    it("fetches rate limit for a tool", async () => {
      const result = await getToolRateLimit("webscraper");
      expect(result).toBeDefined();
      expect(result).toHaveProperty("tool");
      expect(result).toHaveProperty("limit");
      expect(result).toHaveProperty("remaining");
    });
  });

  // ─── getCacheStats ──────────────────────────────────────────────
  describe("getCacheStats", () => {
    it("fetches cache statistics", async () => {
      const result = await getCacheStats();
      expect(result).toBeDefined();
      expect(result).toHaveProperty("size");
      expect(result).toHaveProperty("hits");
      expect(result).toHaveProperty("misses");
      expect(result).toHaveProperty("hitRate");
    });
  });

  // ─── getToolHistory ─────────────────────────────────────────────
  describe("getToolHistory", () => {
    it("fetches tool execution history", async () => {
      const result = await getToolHistory("conv1");
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });
  });

  // ─── getToolCosts ───────────────────────────────────────────────
  describe("getToolCosts", () => {
    it("fetches global tool cost summary", async () => {
      const result = await getToolCosts();
      expect(result).toBeDefined();
      expect(result).toHaveProperty("totalCost");
      expect(result).toHaveProperty("summary");
    });
  });
});
