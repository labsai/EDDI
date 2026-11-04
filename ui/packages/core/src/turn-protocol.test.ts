/* ──────────────────────────────────────────────
   The turn protocol — the union of the 1:1 loops' protocol cases
   Each of the five turn loops (the Manager's ChatPanel `use-chat.ts`, the
   Operator `use-operator-chat.ts`, the Workforce member thread, and the
   widget's ChatWidget + chat-store) encoded these rules in its own suite, in
   terms of its own UI. Here they are once, at the protocol level, so the shared
   API cannot drift from any of them. The source suite is named on each case.
   ────────────────────────────────────────────── */

import { afterEach, describe, expect, it } from "vitest";
import { ApiError, HttpClient, setBaseUrl } from "./http";
import { createConversationApi } from "./conversation";
import { cancelConversation, resumeConversation } from "./hitl";
import { readSseStream } from "./sse";
import {
  UNCONSUMED_STREAM_ERROR_CODES,
  findInputField,
  isSkippedTurn,
  isTurnPaused,
  parseDoneSnapshot,
  parseErrorEvent,
} from "./stream-events";
import { captureFetch, mockFetchResponse, mockFetchSSE, sseStream } from "./test-utils/sse";
import type { SSEEvent } from "./types";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
  setBaseUrl("");
});

const api = createConversationApi(new HttpClient());

async function drain(gen: AsyncGenerator<SSEEvent>): Promise<SSEEvent[]> {
  const events: SSEEvent[] = [];
  for await (const event of gen) events.push(event);
  return events;
}

describe("a refused send was never consumed (409 restores the draft)", () => {
  // use-chat-rejected-send, workforce-thread-retry, ChatWidget.review-fixes:
  // the client withdraws the optimistic bubble and hands the text back. The
  // protocol's part is that the refusal is a typed status with the body kept.
  it("non-streaming: a 409 is an ApiError carrying the status and the server's body", async () => {
    mockFetchResponse(409, "Conversation is awaiting approval; POST …/resume");

    const error = await api.sendMessage("c1", "hello").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).body).toContain("resume");
  });

  it("streaming: a refusal before the first frame throws on the first pull and yields nothing", async () => {
    mockFetchResponse(409, '{"error":"busy"}');

    const events: SSEEvent[] = [];
    const error = await (async () => {
      for await (const event of api.sendMessageStreaming("c1", "hello")) events.push(event);
    })().catch((e: unknown) => e);

    expect(events).toEqual([]);
    expect(error).toMatchObject({ status: 409 });
  });

  it("streaming: an error frame with a pre-turn code is unconsumed; one without a code ran", async () => {
    // use-chat-sse-handling, ChatWidget.chat-ui-review: an `error` frame whose
    // code is one of the backend's pre-turn refusals is treated like a 409.
    const refused = parseErrorEvent('{"message":"Waiting for approval","code":"awaiting_approval"}');
    const failed = parseErrorEvent('{"message":"model timed out"}');

    expect(refused.code !== null && UNCONSUMED_STREAM_ERROR_CODES.has(refused.code)).toBe(true);
    expect(failed.code).toBeNull();
    expect(failed.message).toBe("model timed out");
  });
});

describe("dropped vs paused turns", () => {
  // ChatWidget.test, use-chat-load-pause, workforce-thread: a turn that ends
  // AWAITING_HUMAN with no tokens is ambiguous; the state held before sending
  // separates "this turn caused the pause" from "dropped into a paused one".
  const pausedDone = parseDoneSnapshot('{"conversationState":"AWAITING_HUMAN","conversationOutputs":[]}');

  it("is paused, not dropped, when the conversation was READY before the send", () => {
    expect(isTurnPaused(pausedDone, "READY")).toBe(true);
    expect(isSkippedTurn(pausedDone, 0, "READY")).toBe(false);
  });

  it("is dropped when the conversation was already paused", () => {
    expect(isTurnPaused(pausedDone, "AWAITING_HUMAN")).toBe(false);
    expect(isSkippedTurn(pausedDone, 0, "AWAITING_HUMAN")).toBe(true);
  });

  it("is dropped when the turn's own done still says IN_PROGRESS", () => {
    expect(isSkippedTurn(parseDoneSnapshot('{"conversationState":"IN_PROGRESS"}'), 0, "READY")).toBe(true);
  });

  it("is never dropped once a token streamed", () => {
    expect(isSkippedTurn(pausedDone, 3, "AWAITING_HUMAN")).toBe(false);
  });

  it("ENDED is a drop only for a send into an already-ended conversation", () => {
    const ended = parseDoneSnapshot('{"conversationState":"ENDED"}');
    expect(isSkippedTurn(ended, 0, "READY")).toBe(false);
    expect(isSkippedTurn(ended, 0, "ENDED")).toBe(true);
  });
});

describe("inputField arrives on done", () => {
  // use-chat-secret-input, SecretInput / ChatWidget.ux: streaming is the
  // default, so a field requested only on the non-streaming snapshot never
  // appeared and a password went into the plain textarea.
  it("is read from the done frame's last output", async () => {
    mockFetchSSE([
      "event: token\ndata: Your key?\n\n",
      'event: done\ndata: {"conversationState":"READY","conversationOutputs":[{"output":[{"type":"text","text":"Your key?"},{"type":"inputField","subType":"password","label":"API key"}]}]}\n\n',
    ]);

    const events = await drain(api.sendMessageStreaming("c1", "connect"));
    const done = parseDoneSnapshot(events.find((e) => e.type === "done")!.data);
    const outputs = done?.conversationOutputs ?? [];

    expect(findInputField(outputs[outputs.length - 1]?.output)).toEqual({
      subType: "password",
      label: "API key",
      placeholder: undefined,
      defaultValue: undefined,
    });
  });
});

describe("undo, redo, rerun and cancel", () => {
  // chat.test (Manager) and chat-api.test (widget): undo/redo answer an EMPTY
  // 200 when they moved the step and 409 when there was nothing to move.
  it("undo and redo resolve null on the empty 200", async () => {
    mockFetchResponse(200, "");
    await expect(api.undoConversation("c1")).resolves.toBeNull();
    await expect(api.redoConversation("c1")).resolves.toBeNull();
  });

  it("undo and redo throw with status 409 when there is nothing to move", async () => {
    mockFetchResponse(409, "");
    await expect(api.undoConversation("c1")).rejects.toMatchObject({ status: 409 });
    await expect(api.redoConversation("c1")).rejects.toMatchObject({ status: 409 });
  });

  it("rerun sends the projection the Manager asks for, and a language only when given", async () => {
    const { calls } = captureFetch(200, "");

    await api.rerunLastStep("c1", { returnDetailed: false, returnCurrentStepOnly: true });

    expect(calls[0]!.url).toBe("/agents/c1/rerun?returnDetailed=false&returnCurrentStepOnly=true");
    expect(calls[0]!.url).not.toContain("language");
  });

  it("cancel posts to /cancel and surfaces 'nothing to cancel' as a 409", async () => {
    const { calls } = captureFetch(200, "");
    await cancelConversation("c/1");
    expect(calls[0]!.url).toBe("/agents/c%2F1/cancel");
    expect(calls[0]!.init?.method).toBe("POST");

    mockFetchResponse(409, "");
    await expect(cancelConversation("c1")).rejects.toMatchObject({ status: 409 });
  });

  it("resume posts the decision as JSON", async () => {
    const { calls } = captureFetch(200, "");

    await resumeConversation("c1", { verdict: "APPROVED", pauseId: "p-1" });

    expect(calls[0]!.url).toBe("/agents/c1/resume");
    expect(new Headers(calls[0]!.init?.headers).get("Content-Type")).toBe("application/json");
    expect(JSON.parse(String(calls[0]!.init?.body))).toEqual({ verdict: "APPROVED", pauseId: "p-1" });
  });
});

describe("HttpClient — injected auth, transport and errors", () => {
  it("sends the provider's token, and repeats a 401 once with the fresh one", async () => {
    let token = "stale";
    const seen: (string | null)[] = [];
    globalThis.fetch = ((_url: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get("Authorization");
      seen.push(auth);
      return Promise.resolve(new Response("{}", { status: auth === "Bearer fresh" ? 200 : 401 }));
    }) as typeof fetch;
    const http = new HttpClient({
      auth: {
        getToken: async () => token,
        onUnauthorized: async (rejected) => {
          expect(rejected).toBe("stale");
          token = "fresh";
          return true;
        },
      },
    });

    await http.request("/x", undefined, "ctx");

    expect(seen).toEqual(["Bearer stale", "Bearer fresh"]);
  });

  it("lets the 401 surface when the provider cannot renew", async () => {
    mockFetchResponse(401, "");
    const http = new HttpClient({ auth: { getToken: () => "t", onUnauthorized: async () => false } });

    await expect(http.request("/x", undefined, "ctx")).rejects.toMatchObject({ status: 401 });
  });

  it("dispatches in the same tick when the token is synchronous", () => {
    const { calls } = captureFetch(200, "{}");
    const http = new HttpClient({ auth: { getToken: () => "t" } });

    void http.request("/x", undefined, "ctx");

    expect(calls).toHaveLength(1);
  });

  it("uses the app's transport and error factory", async () => {
    class AppError extends Error {
      constructor(readonly status: number, message: string) {
        super(message);
      }
    }
    const urls: string[] = [];
    const http = new HttpClient({
      baseUrl: "https://eddi.example/",
      fetch: async (url) => {
        urls.push(url);
        return new Response('{"error":"Agent is not deployed"}', { status: 409, statusText: "Conflict" });
      },
      createError: async (res, context) => new AppError(res.status, `${context}: ${JSON.parse(await res.text()).error}`),
    });

    const error = await drain(createConversationApi(http).sendMessageStreaming("c1", "hi")).catch((e: unknown) => e);

    expect(urls).toEqual(["https://eddi.example/agents/c1/stream"]);
    expect(error).toBeInstanceOf(AppError);
    expect((error as AppError).message).toBe("Streaming failed: Agent is not deployed");
  });
});

describe("readSseStream — framing the three readers disagreed on", () => {
  function bytes(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
    return new ReadableStream({
      start(controller) {
        chunks.forEach((c) => controller.enqueue(c));
        controller.close();
      },
    });
  }

  async function frames(body: ReadableStream<Uint8Array>, defaultEventType: string | null) {
    const out: { type: string; data: string }[] = [];
    for await (const frame of readSseStream(body, { defaultEventType })) out.push(frame);
    return out;
  }

  it("splits a frame whose CRLF straddles two reads (the group reader's fix)", async () => {
    expect(await frames(sseStream(["event: a\r\ndata: 1\r", "\n\r\nevent: b\r\ndata: 2\r\n\r\n"]), null)).toEqual([
      { type: "a", data: "1" },
      { type: "b", data: "2" },
    ]);
  });

  it("accepts bare CR line endings", async () => {
    expect(await frames(sseStream(["event: a\rdata: 1\r\r"]), null)).toEqual([{ type: "a", data: "1" }]);
  });

  it("skips frames without an event line when there is no default (group streams)", async () => {
    expect(await frames(sseStream(["data: orphan\n\n", "event: phase_start\ndata: {}\n\n"]), null)).toEqual([
      { type: "phase_start", data: "{}" },
    ]);
  });

  it("names them with the default when there is one (the agent stream's token)", async () => {
    expect(await frames(sseStream(["data: hi\n\n"]), "token")).toEqual([{ type: "token", data: "hi" }]);
  });

  it("keeps a multi-byte character split across the last two reads of an unterminated frame", async () => {
    const all = new TextEncoder().encode("event: token\ndata: Ship 🚀");
    const split = all.length - 2;

    expect(await frames(bytes([all.slice(0, split), all.slice(split)]), "token")).toEqual([
      { type: "token", data: "Ship 🚀" },
    ]);
  });

  it("refuses a response without a body", async () => {
    await expect(frames(null as unknown as ReadableStream<Uint8Array>, "token")).rejects.toThrow("No readable stream");
  });
});
