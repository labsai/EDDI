import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  getConversationDescriptors,
  getActiveConversations,
  endActiveConversations,
  purgeEndedConversations,
  type ConversationStatus,
} from "../conversations";

describe("getConversationDescriptors — pagination & filter params", () => {
  it("sends index (page) and limit query params", async () => {
    let captured: URLSearchParams | null = null;
    server.use(
      http.get("*/conversationstore/conversations", ({ request }) => {
        captured = new URL(request.url).searchParams;
        return HttpResponse.json([]);
      })
    );
    await getConversationDescriptors(100, 3);
    expect(captured!.get("limit")).toBe("100");
    expect(captured!.get("index")).toBe("3");
  });

  it("passes agentVersion, conversationState and viewState params", async () => {
    let captured: URLSearchParams | null = null;
    server.use(
      http.get("*/conversationstore/conversations", ({ request }) => {
        captured = new URL(request.url).searchParams;
        return HttpResponse.json([]);
      })
    );
    await getConversationDescriptors(
      20,
      0,
      "",
      "agent1",
      2,
      "EXECUTION_INTERRUPTED",
      "UNSEEN"
    );
    expect(captured!.get("agentId")).toBe("agent1");
    expect(captured!.get("agentVersion")).toBe("2");
    expect(captured!.get("conversationState")).toBe("EXECUTION_INTERRUPTED");
    expect(captured!.get("viewState")).toBe("UNSEEN");
  });
});

describe("getActiveConversations", () => {
  it("GETs /active/{agentId} with the required agentVersion", async () => {
    let capturedUrl = "";
    server.use(
      http.get("*/conversationstore/conversations/active/:agentId", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([
          {
            conversationId: "conv-a",
            agentId: "agent1",
            agentVersion: 3,
            conversationState: "IN_PROGRESS",
            lastInteraction: Date.now(),
          },
        ]);
      })
    );
    const result = await getActiveConversations("agent1", 3);
    expect(capturedUrl).toContain("/conversationstore/conversations/active/agent1");
    expect(capturedUrl).toContain("agentVersion=3");
    expect(result).toHaveLength(1);
    expect(result[0]!.conversationId).toBe("conv-a");
  });
});

describe("endActiveConversations", () => {
  it("POSTs the ConversationStatus[] body to /end", async () => {
    let body: unknown = null;
    server.use(
      http.post("*/conversationstore/conversations/end", async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 200 });
      })
    );
    const statuses: ConversationStatus[] = [
      {
        conversationId: "conv-a",
        agentId: "agent1",
        agentVersion: 3,
        conversationState: "AWAITING_HUMAN",
        lastInteraction: 123,
      },
    ];
    await expect(endActiveConversations(statuses)).resolves.toBeUndefined();
    expect(body).toEqual(statuses);
  });
});

describe("purgeEndedConversations", () => {
  it("DELETEs with deleteOlderThanDays and returns the purged count", async () => {
    let capturedUrl = "";
    server.use(
      http.delete("*/conversationstore/conversations/", ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json(7);
      })
    );
    const count = await purgeEndedConversations(30);
    expect(capturedUrl).toContain("deleteOlderThanDays=30");
    expect(count).toBe(7);
  });
});
