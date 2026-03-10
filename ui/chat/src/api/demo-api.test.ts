import { describe, it, expect } from "vitest";
import { demoStartConversation, demoSendMessageStreaming, demoGetQuickReplies } from "./demo-api";

describe("demo-api", () => {
  describe("demoStartConversation", () => {
    it("returns a conversationId, welcomeMessage, and quickReplies", async () => {
      const result = await demoStartConversation();
      expect(result.conversationId).toBeTruthy();
      expect(result.welcomeMessage).toBeDefined();
      expect(result.welcomeMessage.role).toBe("bot");
      expect(result.welcomeMessage.content).toBeTruthy();
      expect(result.quickReplies).toBeDefined();
      expect(result.quickReplies.length).toBeGreaterThan(0);
    });
  });

  describe("demoSendMessageStreaming", () => {
    it("yields SSE events for a known message", async () => {
      const events: Array<{ type: string; data: string }> = [];
      for await (const event of demoSendMessageStreaming("Tell me about EDDI")) {
        events.push(event);
      }
      // Should have at least a thinking event, some tokens, and a done event
      expect(events.length).toBeGreaterThan(0);
      expect(events.some((e) => e.type === "done")).toBe(true);
    }, 15000);

    it("yields events for an unknown message with a default response", async () => {
      const events: Array<{ type: string; data: string }> = [];
      for await (const event of demoSendMessageStreaming("random gibberish")) {
        events.push(event);
      }
      expect(events.length).toBeGreaterThan(0);
      expect(events.some((e) => e.type === "done")).toBe(true);
    });
  });

  describe("demoGetQuickReplies", () => {
    it("returns an array of quick replies", () => {
      const replies = demoGetQuickReplies("any-id");
      expect(Array.isArray(replies)).toBe(true);
      expect(replies.length).toBeGreaterThan(0);
      expect(replies[0]).toHaveProperty("value");
    });
  });
});
