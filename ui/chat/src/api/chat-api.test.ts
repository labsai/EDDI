/* ──────────────────────────────────────────────
   chat-api — SSE parsing and HTTP status handling
   ────────────────────────────────────────────── */

import { describe, it, expect, afterEach } from "vitest";
import {
  sendMessageStreaming,
  undoConversation,
  rerunLastStep,
  loadManagedConversation,
  sendManagedAgentMessage,
  setBaseUrl,
} from "./chat-api";
import { mockFetchSSE, mockFetchResponse, captureFetch } from "@/test-utils/sse";
import type { SSEEvent } from "@/types";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

async function collect(chunks: string[]): Promise<SSEEvent[]> {
  mockFetchSSE(chunks);
  const events: SSEEvent[] = [];
  for await (const event of sendMessageStreaming("", "", "conv-1", "hi")) {
    events.push(event);
  }
  return events;
}

describe("sendMessageStreaming — SSE parsing", () => {
  it("keeps a leading space — RESTEasy writes no delimiter space", async () => {
    // EDDI's writer is resteasy-reactive SseUtil.serialiseField, which does
    // `sb.append(field).append(":")` then the value — NO delimiter space
    // (verified in resteasy-reactive-3.37.1 sources, SseUtil.java:84).
    // A leading space on the wire therefore belongs to the token, and LLM
    // streams emit " word" constantly. Stripping one per the SSE spec's
    // server-side convention deleted it and ran words together.
    const events = await collect(["event: token\ndata: world\n\n"]);

    expect(events).toEqual([{ type: "token", data: " world" }]);
  });

  it("preserves deeper indentation verbatim", async () => {
    const events = await collect(["event: token\ndata:    indented\n\n"]);

    expect(events).toEqual([{ type: "token", data: "    indented" }]);
  });

  it("preserves trailing whitespace in a token payload", async () => {
    const events = await collect(["event: token\ndata:word \n\n"]);

    expect(events).toEqual([{ type: "token", data: "word " }]);
  });

  it("joins multi-line data with newlines, preserving each line's indentation", async () => {
    const events = await collect([
      "event: token\ndata:def f():\ndata:    return 1\n\n",
    ]);

    expect(events).toEqual([
      { type: "token", data: "def f():\n    return 1" },
    ]);
  });

  it("reassembles a frame split across chunk boundaries", async () => {
    const events = await collect(["event: tok", "en\ndata:hello", "\n\n"]);

    expect(events).toEqual([{ type: "token", data: "hello" }]);
  });

  it("normalizes CRLF line endings", async () => {
    const events = await collect(["event: token\r\ndata:hi\r\n\r\n"]);

    expect(events).toEqual([{ type: "token", data: "hi" }]);
  });

  it("surfaces an unknown event type rather than mislabelling it a token", async () => {
    // The parser used to default eventType to "token", so any unrecognised
    // event's payload was appended to the agent's message as visible text.
    const events = await collect(["event: cascade_step_start\ndata:{}\n\n"]);

    expect(events).toEqual([{ type: "cascade_step_start", data: "{}" }]);
  });

  it("emits every event type the backend can send", async () => {
    const events = await collect([
      "event: task_start\ndata: {}\n\n",
      "event: task_complete\ndata: {}\n\n",
      "event: task_failed\ndata: {}\n\n",
      "event: cascade_escalation\ndata: {}\n\n",
      "event: done\ndata: {}\n\n",
    ]);

    expect(events.map((e) => e.type)).toEqual([
      "task_start",
      "task_complete",
      "task_failed",
      "cascade_escalation",
      "done",
    ]);
  });

  it("does not emit a frame that carries no data and no event name", async () => {
    const events = await collect([": keepalive comment\n\n"]);

    expect(events).toEqual([]);
  });
});

describe("undoConversation — empty response bodies", () => {
  it("resolves on a 200 with an empty body", async () => {
    // The backend returns 200 with NO body for undo/redo. Calling res.json()
    // on that throws, which surfaced to the user as a failed undo.
    setBaseUrl("");
    mockFetchResponse(200, "");

    await expect(undoConversation("", "", "conv-1")).resolves.toBeDefined();
  });

  it("throws an ApiError carrying status 409 when undo is refused", async () => {
    setBaseUrl("");
    mockFetchResponse(409, "");

    await expect(undoConversation("", "", "conv-1")).rejects.toMatchObject({
      status: 409,
    });
  });
});

describe("rerunLastStep", () => {
  it("posts to the rerun endpoint so a failed turn can be retried", async () => {
    setBaseUrl("");
    const { calls } = captureFetch(200, "{}");

    await rerunLastStep("conv-1");

    expect(calls[0].url).toContain("/agents/conv-1/rerun");
    expect(calls[0].init?.method).toBe("POST");
    // `language` has no @DefaultValue server-side and is checked before any
    // other work, so omitting it is an unconditional 400.
    expect(calls[0].url).toContain("language=en");
  });

  it("tolerates an empty 200 body", async () => {
    setBaseUrl("");
    mockFetchResponse(200, "");

    await expect(rerunLastStep("conv-1")).resolves.toBeDefined();
  });

  it("surfaces a refusal with its status", async () => {
    setBaseUrl("");
    mockFetchResponse(409, "");

    await expect(rerunLastStep("conv-1")).rejects.toMatchObject({ status: 409 });
  });
});

describe("managed-agent conversation", () => {
  it("loads an existing conversation with GET", async () => {
    setBaseUrl("");
    const { calls } = captureFetch(200, '{"conversationId":"c1"}');

    await loadManagedConversation("support", "user-7");

    expect(calls[0].init?.method).toBe("GET");
  });

  it("POSTs an attachment-only turn instead of falling back to GET", async () => {
    // Verb selection used to hinge on the truthiness of `message`, so a turn
    // carrying only attachments issued a GET — sending nothing and re-reading
    // the whole conversation.
    setBaseUrl("");
    const { calls } = captureFetch(200, '{"conversationId":"c1"}');

    await sendManagedAgentMessage("support", "user-7", "", {
      attachment_0: { type: "object", value: { storageRef: "r1", fileName: "a.pdf" } },
    });

    expect(calls[0].init?.method).toBe("POST");
  });

  it("carries the attachment context on the wire", async () => {
    setBaseUrl("");
    const { calls } = captureFetch(200, '{"conversationId":"c1"}');

    await sendManagedAgentMessage("support", "user-7", "review this", {
      attachment_0: { type: "object", value: { storageRef: "r1", fileName: "a.pdf" } },
    });

    const body = JSON.parse(String(calls[0].init?.body));
    expect(body.context.attachment_0.value.storageRef).toBe("r1");
  });
});
