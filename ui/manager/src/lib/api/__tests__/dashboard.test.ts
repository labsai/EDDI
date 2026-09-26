import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import { getDashboardStats } from "../dashboard";

describe("dashboard API", () => {
  describe("getDashboardStats", () => {
    it("aggregates stats from multiple endpoints", async () => {
      const result = await getDashboardStats();
      expect(result).toBeDefined();
      expect(result).toHaveProperty("agentCount");
      expect(result).toHaveProperty("workflowCount");
      expect(result).toHaveProperty("conversationCount");
      expect(result).toHaveProperty("resourceCount");
      expect(result.agentCount).toBeGreaterThan(0);
      expect(result.workflowCount).toBeGreaterThan(0);
      expect(result.conversationCount).toBeGreaterThan(0);
      expect(result.resourceCount).toBe(0); // always 0 per source code
    });

    it("deduplicates agents by ID (versions of same agent count as one)", async () => {
      server.use(
        http.get("*/agentstore/agents/descriptors", () =>
          HttpResponse.json([
            {
              resource: "eddi://ai.labs.agent/agentstore/agents/a?version=1",
              name: "Agent A",
              createdOn: 1000,
              lastModifiedOn: 1000,
            },
            {
              resource: "eddi://ai.labs.agent/agentstore/agents/a?version=2",
              name: "Agent A",
              createdOn: 1000,
              lastModifiedOn: 2000,
            },
            {
              resource: "eddi://ai.labs.agent/agentstore/agents/b?version=1",
              name: "Agent B",
              createdOn: 1000,
              lastModifiedOn: 1000,
            },
          ])
        )
      );
      const result = await getDashboardStats();
      expect(result.agentCount).toBe(2); // a and b, not 3
    });

    it("asks for the backend's conversation ceiling and flags a full page as a lower bound", async () => {
      // RestConversationStore clamps `limit` to 100. Asking for 1000 got 100,
      // and the dashboard showed "100" as if it were the total.
      let askedLimit: string | null = null;
      server.use(
        http.get("*/conversationstore/conversations", ({ request }) => {
          askedLimit = new URL(request.url).searchParams.get("limit");
          return HttpResponse.json(
            Array.from({ length: 100 }, (_, i) => ({
              resource: `eddi://ai.labs.conversation/conversationstore/conversations/c${i}`,
            })),
          );
        }),
      );
      const result = await getDashboardStats();
      expect(askedLimit).toBe("100");
      expect(result.conversationCount).toBe(100);
      expect(result.conversationCountCapped).toBe(true);
      expect(result.agentCountCapped).toBe(false);
    });

    it("does not flag a count that did not fill its page", async () => {
      const result = await getDashboardStats();
      expect(result.conversationCountCapped).toBe(false);
    });

    it("handles partial failure gracefully (catch → [])", async () => {
      server.use(
        http.get("*/agentstore/agents/descriptors", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        ),
        http.get("*/workflowstore/workflows/descriptors", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        ),
        http.get("*/conversationstore/conversations", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      const result = await getDashboardStats();
      expect(result.agentCount).toBe(0);
      expect(result.workflowCount).toBe(0);
      expect(result.conversationCount).toBe(0);
    });
  });
});
