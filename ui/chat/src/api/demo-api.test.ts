import { describe, it, expect } from "vitest";
import {
  createDemoTransport,
  demoStartConversation,
  demoSendMessageStreaming,
  demoGetQuickReplies,
} from "./demo-api";

describe("demo-api", () => {
  describe("demoStartConversation", () => {
    it("returns a conversationId, welcomeMessage, and quickReplies", async () => {
      const result = await demoStartConversation();
      expect(result.conversationId).toBeTruthy();
      expect(result.welcomeMessage).toBeDefined();
      expect(result.welcomeMessage.role).toBe("agent");
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
      // Same explicit timeout as the sibling test above: the demo stream uses
      // real delays totalling ~5s, so the 5s default makes this flaky under
      // parallel load rather than genuinely failing.
    }, 15000);
  });

  describe("demoSendMessageStreaming — cancellation", () => {
    it("ends at once when the turn is stopped during the scripted delay", async () => {
      const abort = new AbortController();
      const events: Array<{ type: string }> = [];
      const started = Date.now();
      for await (const event of demoSendMessageStreaming("What about streaming?", abort.signal)) {
        events.push(event);
        // Stop while the 1.5 s "thinking" delay is running.
        if (event.type === "task_start") setTimeout(() => abort.abort(), 20);
      }
      expect(events.map((e) => e.type)).toEqual(["task_start"]);
      expect(Date.now() - started).toBeLessThan(1000);
    });

    it("yields nothing for a turn already stopped", async () => {
      const abort = new AbortController();
      abort.abort();
      const events: unknown[] = [];
      for await (const event of demoSendMessageStreaming("What about streaming?", abort.signal)) events.push(event);
      expect(events).toEqual([]);
    });

    it("the demo transport hands the turn's signal to the stream", async () => {
      const abort = new AbortController();
      const stream = createDemoTransport().stream!(null, "What about streaming?", { signal: abort.signal });
      const types: string[] = [];
      for await (const event of stream) {
        types.push(event.type);
        abort.abort();
      }
      expect(types).toEqual(["task_start"]);
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
