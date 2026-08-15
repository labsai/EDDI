import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  parseConversationIdFromLocation,
  startConversation,
  readConversation,
  sendMessage,
  sendMessageWithContext,
  sendMessageStreaming,
  endConversation,
  undoConversation,
  redoConversation,
  rerunLastStep,
  type SSEEvent,
} from "../chat";
import { OPERATOR_PROBE_USER_ID } from "../operator";

// ─── Pure function tests ──────────────────────────────────────────

describe("parseConversationIdFromLocation", () => {
  it("parses ID from a path", () => {
    expect(parseConversationIdFromLocation("/agents/conv-123")).toBe(
      "conv-123"
    );
  });

  it("strips query params from last segment", () => {
    expect(
      parseConversationIdFromLocation(
        "/agents/conv-abc?returnDetailed=false"
      )
    ).toBe("conv-abc");
  });

  it("handles plain ID with no path", () => {
    expect(parseConversationIdFromLocation("conv-plain")).toBe("conv-plain");
  });

  it("handles trailing slash path", () => {
    // "/agents/" → last segment is "", fallback to location
    const result = parseConversationIdFromLocation("/agents/");
    expect(result).toBeDefined();
  });
});

// ─── API function tests ───────────────────────────────────────────

describe("startConversation", () => {
  it("starts a conversation and returns conversation ID", async () => {
    const result = await startConversation("production", "agent1");
    expect(result).toBeDefined();
    expect(typeof result).toBe("string");
    expect(result.length).toBeGreaterThan(0);
  });

  it("handles API error", async () => {
    server.use(
      http.post("*/agents/:agentId/start", () =>
        HttpResponse.json({ message: "Error" }, { status: 500 })
      )
    );
    await expect(
      startConversation("production", "agent-fail")
    ).rejects.toMatchObject({ status: 500 });
  });

  /**
   * The `userId` query parameter is the whole mechanism that keeps activation's
   * probe conversations out of "restore my last conversation" — the probes stamp
   * `OPERATOR_PROBE_USER_ID` and recovery filters on it. Every other test in
   * that chain asserts on already-tagged descriptor fixtures, so removing or
   * misspelling this line would leave all of them green while the filter
   * silently stopped matching anything. This is the one place the request itself
   * is checked.
   */
  it("passes userId through as a query parameter when given", async () => {
    let seen: string | null = null;
    server.use(
      http.post("*/agents/:agentId/start", ({ request }) => {
        seen = new URL(request.url).searchParams.get("userId");
        return HttpResponse.json({ location: "/agents/conv-tagged" });
      })
    );

    await startConversation("production", "agent1", OPERATOR_PROBE_USER_ID);

    expect(seen).toBe(OPERATOR_PROBE_USER_ID);
  });

  it("sends no userId parameter at all when none is given", async () => {
    // An admin's own conversation must stay untagged, or recovery would filter
    // out the very conversation it exists to restore.
    let hadParam = true;
    server.use(
      http.post("*/agents/:agentId/start", ({ request }) => {
        hadParam = new URL(request.url).searchParams.has("userId");
        return HttpResponse.json({ location: "/agents/conv-plain" });
      })
    );

    await startConversation("production", "agent1");

    expect(hadParam).toBe(false);
  });
});

describe("readConversation", () => {
  it("reads a conversation snapshot", async () => {
    const result = await readConversation(
      "production",
      "agent1",
      "conv-mock"
    );
    expect(result).toBeDefined();
    expect(result.conversationState).toBe("READY");
  });

  it("passes returnCurrentStepOnly parameter", async () => {
    const result = await readConversation(
      "production",
      "agent1",
      "conv-mock",
      true
    );
    expect(result).toBeDefined();
  });
});

describe("sendMessage", () => {
  it("sends a plain text message and returns snapshot", async () => {
    const result = await sendMessage(
      "production",
      "agent1",
      "conv-mock",
      "Hello"
    );
    expect(result).toBeDefined();
    expect(result.conversationState).toBeDefined();
  });

  it("handles non-ok response", async () => {
    server.use(
      http.post("*/agents/:conversationId", () =>
        new HttpResponse(null, { status: 500, statusText: "Internal Server Error" })
      )
    );
    await expect(
      sendMessage("production", "agent1", "conv-fail", "Hello")
    ).rejects.toMatchObject({ status: 500 });
  });
});

describe("sendMessageWithContext", () => {
  it("sends a message with context data", async () => {
    const result = await sendMessageWithContext(
      "production",
      "agent1",
      "conv-mock",
      { input: "Hello", context: { key: "value" } }
    );
    expect(result).toBeDefined();
    expect(result.conversationState).toBeDefined();
  });
});

describe("endConversation", () => {
  it("ends a conversation", async () => {
    // The POST /agents/:conversationId/endConversation may be caught by the
    // generic POST /agents/:conversationId handler. Let's add a specific one.
    server.use(
      http.post("*/agents/:conversationId/endConversation", () =>
        new HttpResponse(null, { status: 200, headers: { "Content-Length": "0" } })
      )
    );
    await expect(endConversation("conv-mock")).resolves.toBeUndefined();
  });
});

describe("undoConversation", () => {
  it("undoes the last step", async () => {
    server.use(
      http.post("*/agents/:conversationId/undo", () =>
        HttpResponse.json({
          agentId: "agent1",
          agentVersion: 3,
          conversationId: "conv-mock",
          conversationState: "READY",
          environment: "production",
          conversationSteps: [],
          undoAvailable: false,
          redoAvailable: true,
        })
      )
    );
    const result = await undoConversation("production", "agent1", "conv-mock");
    expect(result).toBeDefined();
    expect(result.redoAvailable).toBe(true);
  });
});

describe("redoConversation", () => {
  it("redoes a previously undone step", async () => {
    server.use(
      http.post("*/agents/:conversationId/redo", () =>
        HttpResponse.json({
          agentId: "agent1",
          agentVersion: 3,
          conversationId: "conv-mock",
          conversationState: "READY",
          environment: "production",
          conversationSteps: [],
          undoAvailable: true,
          redoAvailable: false,
        })
      )
    );
    const result = await redoConversation("production", "agent1", "conv-mock");
    expect(result).toBeDefined();
    expect(result.undoAvailable).toBe(true);
  });
});

describe("rerunLastStep", () => {
  it("reruns the last step", async () => {
    server.use(
      http.post("*/agents/:conversationId/rerun", () =>
        new HttpResponse(null, { status: 200, headers: { "Content-Length": "0" } })
      )
    );
    await expect(rerunLastStep("conv-mock")).resolves.toBeUndefined();
  });
});

// ─── SSE streaming tests ──────────────────────────────────────────

describe("sendMessageStreaming", () => {
  it("yields parsed SSE events from stream", async () => {
    const sseBody =
      "event: token\ndata: Hello\n\n" +
      "event: token\ndata: World\n\n" +
      "event: done\ndata: \n\n";

    server.use(
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse(sseBody, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        })
      )
    );

    const events: SSEEvent[] = [];
    for await (const event of sendMessageStreaming(
      "production",
      "agent1",
      "conv-mock",
      { input: "Hello" }
    )) {
      events.push(event);
    }

    expect(events.length).toBe(3);
    expect(events[0]).toEqual({ type: "token", data: "Hello" });
    expect(events[1]).toEqual({ type: "token", data: "World" });
    expect(events[2]).toEqual({ type: "done", data: "" });
  });

  it("emits a multi-byte final character from a chunked stream with no trailing blank line", async () => {
    // Covers the tail-frame emit: the body ends without a blank line, so the last
    // frame only surfaces because of the post-loop parseSseFrame(buffer).
    //
    // The bytes are split mid-emoji, but MSW/undici reassembles the body before
    // our reader sees it, so this does not exercise a real chunk boundary — it
    // pins the tail-frame emit only. Removing that emit turns this red; nothing
    // about TextDecoder behaviour is asserted here.
    const bytes = new TextEncoder().encode("event: token\ndata: Ship 🚀");
    const splitAt = bytes.length - 2; // mid-way through the 4-byte emoji

    server.use(
      http.post("*/agents/:conversationId/stream", () => {
        // Deliver the halves as separate reads. Enqueuing both synchronously
        // lets the transport coalesce them, which hides the split entirely and
        // makes this test pass even with the decoder flush removed.
        let sent = 0;
        const stream = new ReadableStream({
          async pull(controller) {
            await new Promise((r) => setTimeout(r, 5));
            if (sent === 0) controller.enqueue(bytes.slice(0, splitAt));
            else if (sent === 1) controller.enqueue(bytes.slice(splitAt));
            else controller.close();
            sent++;
          },
        });
        return new HttpResponse(stream, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        });
      })
    );

    const events: SSEEvent[] = [];
    for await (const event of sendMessageStreaming(
      "production",
      "agent1",
      "conv-mock",
      { input: "Hello" }
    )) {
      events.push(event);
    }

    expect(events).toEqual([{ type: "token", data: "Ship 🚀" }]);
  });

  it("handles task_start and task_complete events", async () => {
    const sseBody =
      "event: task_start\ndata: Processing\n\n" +
      "event: task_complete\ndata: Done\n\n";

    server.use(
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse(sseBody, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        })
      )
    );

    const events: SSEEvent[] = [];
    for await (const event of sendMessageStreaming(
      "production",
      "agent1",
      "conv-mock",
      { input: "test" }
    )) {
      events.push(event);
    }

    expect(events[0]).toEqual({ type: "task_start", data: "Processing" });
    expect(events[1]).toEqual({ type: "task_complete", data: "Done" });
  });

  it("throws on non-ok response", async () => {
    server.use(
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse(null, { status: 500, statusText: "Internal Server Error" })
      )
    );

    await expect(async () => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      for await (const _event of sendMessageStreaming(
        "production",
        "agent1",
        "conv-fail",
        { input: "Hello" }
      )) {
        // Should not reach here
      }
    }).rejects.toMatchObject({ status: 500 });
  });

  it("handles error event type", async () => {
    const sseBody = "event: error\ndata: Something went wrong\n\n";

    server.use(
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse(sseBody, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        })
      )
    );

    const events: SSEEvent[] = [];
    for await (const event of sendMessageStreaming(
      "production",
      "agent1",
      "conv-mock",
      { input: "test" }
    )) {
      events.push(event);
    }

    expect(events[0]).toEqual({ type: "error", data: "Something went wrong" });
  });

  it("defaults to token event type when no event line present", async () => {
    const sseBody = "data: just-data\n\n";

    server.use(
      http.post("*/agents/:conversationId/stream", () =>
        new HttpResponse(sseBody, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        })
      )
    );

    const events: SSEEvent[] = [];
    for await (const event of sendMessageStreaming(
      "production",
      "agent1",
      "conv-mock",
      { input: "test" }
    )) {
      events.push(event);
    }

    expect(events[0]).toEqual({ type: "token", data: "just-data" });
  });
});

// ─── Edge cases ───────────────────────────────────────────────────

describe("parseConversationIdFromLocation edge cases", () => {
  it("handles empty string", () => {
    const result = parseConversationIdFromLocation("");
    expect(result).toBeDefined();
  });

  it("handles deeply nested path", () => {
    expect(
      parseConversationIdFromLocation("/a/b/c/d/conv-deep")
    ).toBe("conv-deep");
  });

  it("handles query params with multiple params", () => {
    expect(
      parseConversationIdFromLocation(
        "/agents/conv-multi?returnDetailed=false&lang=en"
      )
    ).toBe("conv-multi");
  });
});


/**
 * The wire-level half of the environment bug: `startConversation` accepted the
 * environment as `_environment` and never sent it, so the backend's
 * `@DefaultValue("production")` applied to every conversation the Manager
 * started. An agent deployed only to `test` was unreachable, and the failure
 * read as a broken agent rather than as the wrong environment.
 */
describe("startConversation — environment reaches the wire", () => {
  it("sends the requested environment as a query parameter", async () => {
    let seen = "";
    server.use(
      http.post("*/agents/:agentId/start", ({ request }) => {
        seen = new URL(request.url).search;
        return HttpResponse.json({ location: "/agents/conv-1" });
      }),
    );

    await startConversation("test", "agent-1");

    expect(seen).toContain("environment=test");
  });

  it("sends production when production is asked for — never silently omitted", () => {
    // Asserting the positive too: an implementation that drops the parameter
    // "because production is the default anyway" is the bug, not a shortcut.
    return (async () => {
      let seen = "";
      server.use(
        http.post("*/agents/:agentId/start", ({ request }) => {
          seen = new URL(request.url).search;
          return HttpResponse.json({ location: "/agents/conv-2" });
        }),
      );

      await startConversation("production", "agent-1");

      expect(seen).toContain("environment=production");
    })();
  });
});
