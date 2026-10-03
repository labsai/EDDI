import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getConversationCosts,
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
});
