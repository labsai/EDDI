import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getAllTriggers,
  getTrigger,
  createTrigger,
  updateTrigger,
  deleteTrigger,
} from "../triggers";

describe("triggers API", () => {
  describe("getAllTriggers", () => {
    it("fetches all triggers", async () => {
      const result = await getAllTriggers();
      expect(result).toBeDefined();
      expect(Array.isArray(result)).toBe(true);
    });

    it("returns empty array when API returns null", async () => {
      server.use(
        http.get("*/AgentTriggerStore/agenttriggers", () =>
          HttpResponse.json(null)
        )
      );
      const result = await getAllTriggers();
      expect(result).toEqual([]);
    });

    it("handles API error", async () => {
      server.use(
        http.get("*/AgentTriggerStore/agenttriggers", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(getAllTriggers()).rejects.toMatchObject({ status: 500 });
    });
  });

  describe("getTrigger", () => {
    it("fetches a single trigger by intent", async () => {
      const result = await getTrigger("booking_request");
      expect(result).toBeDefined();
      expect(result.intent).toBe("booking_request");
    });

    it("handles 404 for unknown intent", async () => {
      await expect(getTrigger("unknown_intent")).rejects.toMatchObject({
        status: 404,
      });
    });
  });

  describe("createTrigger", () => {
    it("creates a trigger", async () => {
      await expect(
        createTrigger({
          intent: "test-intent",
          agentDeployments: [
            { environment: "production", agentId: "agent1" },
          ],
        })
      ).resolves.toBeUndefined();
    });
  });

  describe("updateTrigger", () => {
    it("updates a trigger", async () => {
      await expect(
        updateTrigger("test-intent", {
          intent: "test-intent",
          agentDeployments: [],
        })
      ).resolves.toBeUndefined();
    });
  });

  describe("deleteTrigger", () => {
    it("deletes a trigger", async () => {
      await expect(deleteTrigger("test-intent")).resolves.toBeUndefined();
    });
  });
});
