import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { BearerEventSource } from "@/lib/bearer-event-source";

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Create a ReadableStream that yields the given chunks */
function createSSEStream(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let index = 0;
  return new ReadableStream({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index]!));
        index++;
      } else {
        controller.close();
      }
    },
  });
}

describe("BearerEventSource", () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.useFakeTimers();
    fetchSpy = vi.spyOn(globalThis, "fetch") as ReturnType<typeof vi.spyOn>;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("connects on construction and fires onopen", async () => {
    const body = createSSEStream([]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } })
    );

    const openFn = vi.fn();
    const es = new BearerEventSource("http://test/sse", { Authorization: "Bearer tok" });
    es.onopen = openFn;

    // Let the connect promise resolve
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchSpy).toHaveBeenCalledWith(
      "http://test/sse",
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: "Bearer tok", Accept: "text/event-stream" }),
      })
    );
    expect(openFn).toHaveBeenCalledTimes(1);

    es.close();
  });

  it("parses SSE data: lines and fires onmessage", async () => {
    const body = createSSEStream(["data: hello world\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    // Allow stream reading to complete
    await vi.advanceTimersByTimeAsync(10);

    expect(msgFn).toHaveBeenCalledTimes(1);
    const event = msgFn.mock.calls[0]![0] as MessageEvent;
    expect(event.data).toBe("hello world");
    expect(event.type).toBe("message");

    es.close();
  });

  it("delivers named events only to their listener, not onmessage", async () => {
    const body = createSSEStream(["event: custom\ndata: payload\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const listenerFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;
    es.addEventListener("custom", listenerFn);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    // Native EventSource semantics: onmessage fires only for the default
    // "message" type, NOT for named events. This is what prevents a caller
    // that registers addEventListener("log") + onmessage (unnamed fallback)
    // from handling a named "log" event twice.
    expect(msgFn).not.toHaveBeenCalled();
    // The named listener receives it exactly once.
    expect(listenerFn).toHaveBeenCalledTimes(1);
    const event = listenerFn.mock.calls[0]![0] as MessageEvent;
    expect(event.type).toBe("custom");
    expect(event.data).toBe("payload");

    es.close();
  });

  it("onmessage still fires for default (unnamed) 'message' events", async () => {
    const body = createSSEStream(["data: fallback\n\n"]);
    fetchSpy.mockResolvedValueOnce(new Response(body, { status: 200 }));

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    // Unnamed events default to type "message" → onmessage fallback fires once.
    expect(msgFn).toHaveBeenCalledTimes(1);
    expect((msgFn.mock.calls[0]![0] as MessageEvent).data).toBe("fallback");

    es.close();
  });

  it("handles multi-line data fields", async () => {
    const body = createSSEStream(["data: line1\ndata: line2\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    const event = msgFn.mock.calls[0]![0] as MessageEvent;
    expect(event.data).toBe("line1\nline2");

    es.close();
  });

  it("handles blocks split across multiple chunks", async () => {
    const body = createSSEStream(["data: hel", "lo\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(msgFn).toHaveBeenCalledTimes(1);
    const event = msgFn.mock.calls[0]![0] as MessageEvent;
    expect(event.data).toBe("hello");

    es.close();
  });

  it("ignores blocks with no data lines", async () => {
    const body = createSSEStream(["event: ping\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(msgFn).not.toHaveBeenCalled();

    es.close();
  });

  it("close() aborts fetch and clears reconnect timer", async () => {
    // First connect fails to trigger reconnect
    fetchSpy.mockResolvedValueOnce(
      new Response(null, { status: 500 })
    );

    const errorFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;

    await vi.advanceTimersByTimeAsync(0);

    // After error, scheduleReconnect sets a 5s timer
    expect(errorFn).toHaveBeenCalled();

    // Close before reconnect fires
    es.close();

    // Advance past the reconnect timer
    fetchSpy.mockClear();
    await vi.advanceTimersByTimeAsync(6000);

    // No new fetch should have been made
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("schedules reconnect on non-OK response", async () => {
    fetchSpy
      .mockResolvedValueOnce(new Response(null, { status: 502 }))
      .mockResolvedValueOnce(
        new Response(createSSEStream([]), { status: 200 })
      );

    const errorFn = vi.fn();
    const openFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;
    es.onopen = openFn;

    await vi.advanceTimersByTimeAsync(0);
    expect(errorFn).toHaveBeenCalledTimes(1);
    expect(openFn).not.toHaveBeenCalled();

    // Advance 5s for reconnect
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(openFn).toHaveBeenCalledTimes(1);

    es.close();
  });

  it("schedules reconnect on fetch error", async () => {
    fetchSpy
      .mockRejectedValueOnce(new Error("Network error"))
      .mockResolvedValueOnce(
        new Response(createSSEStream([]), { status: 200 })
      );

    const errorFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;

    await vi.advanceTimersByTimeAsync(0);
    expect(errorFn).toHaveBeenCalledTimes(1);

    // Reconnect after 5s
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchSpy).toHaveBeenCalledTimes(2);

    es.close();
  });

  it("reconnects on clean EOF", async () => {
    // First response completes cleanly, second one is open
    fetchSpy
      .mockResolvedValueOnce(
        new Response(createSSEStream(["data: first\n\n"]), { status: 200 })
      )
      .mockResolvedValueOnce(
        new Response(createSSEStream([]), { status: 200 })
      );

    const errorFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;

    await vi.advanceTimersByTimeAsync(0);
    // Stream completes → scheduleReconnect → onerror + 5s timer
    await vi.advanceTimersByTimeAsync(10);

    expect(errorFn).toHaveBeenCalled();

    // Reconnect fires after 5s
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchSpy).toHaveBeenCalledTimes(2);

    es.close();
  });

  it("does not reconnect after close()", async () => {
    fetchSpy.mockResolvedValueOnce(
      new Response(createSSEStream(["data: msg\n\n"]), { status: 200 })
    );

    const es = new BearerEventSource("http://test/sse");

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    es.close();

    fetchSpy.mockClear();
    await vi.advanceTimersByTimeAsync(10000);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("does not fire events after close()", async () => {
    // Response that doesn't complete immediately
    let resolveRead: (() => void) | undefined;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        const encoder = new TextEncoder();
        controller.enqueue(encoder.encode("data: msg1\n\n"));
        // Hold the stream open
        new Promise<void>((r) => { resolveRead = r; }).then(() => {
          controller.close();
        });
      },
    });
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(msgFn).toHaveBeenCalledTimes(1);

    es.close();

    // Resolve the stream — no more messages should be dispatched
    resolveRead?.();
    await vi.advanceTimersByTimeAsync(10);

    expect(msgFn).toHaveBeenCalledTimes(1);
  });

  it("ignores AbortError after close", async () => {
    fetchSpy.mockImplementationOnce((_url, init) => {
      const signal = (init as RequestInit)?.signal;
      return new Promise((_resolve, reject) => {
        signal?.addEventListener("abort", () => {
          reject(new DOMException("Aborted", "AbortError"));
        });
      });
    });

    const errorFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;

    await vi.advanceTimersByTimeAsync(0);
    es.close();
    await vi.advanceTimersByTimeAsync(0);

    // onerror should not have been called for intentional abort
    expect(errorFn).not.toHaveBeenCalled();
  });

  it("addEventListener adds a handler for named events", async () => {
    const body = createSSEStream(["event: update\ndata: v2\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const fn1 = vi.fn();
    const fn2 = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.addEventListener("update", fn1);
    es.addEventListener("update", fn2);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(fn1).toHaveBeenCalledTimes(1);
    expect(fn2).toHaveBeenCalledTimes(1);

    es.close();
  });

  it("handles response with no body", async () => {
    // response.body is null
    fetchSpy.mockResolvedValueOnce(
      { ok: true, body: null } as unknown as Response
    );

    const errorFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;

    await vi.advanceTimersByTimeAsync(0);

    // Should trigger reconnect since body is null
    expect(errorFn).toHaveBeenCalled();

    es.close();
  });

  it("handles multiple SSE blocks in one chunk", async () => {
    const body = createSSEStream(["data: first\n\ndata: second\n\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const msgFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(msgFn).toHaveBeenCalledTimes(2);
    expect((msgFn.mock.calls[0]![0] as MessageEvent).data).toBe("first");
    expect((msgFn.mock.calls[1]![0] as MessageEvent).data).toBe("second");

    es.close();
  });

  // ─── CRLF normalization tests ────────────────────────────────────────────

  it("handles \\r\\n line endings in SSE blocks", async () => {
    // Simulate a server sending Windows-style line endings
    const body = createSSEStream(["event: log\r\ndata: crlf-payload\r\n\r\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const listenerFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.addEventListener("log", listenerFn);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(listenerFn).toHaveBeenCalledTimes(1);
    const event = listenerFn.mock.calls[0]![0] as MessageEvent;
    expect(event.type).toBe("log");
    expect(event.data).toBe("crlf-payload");

    es.close();
  });

  it("handles lone \\r line endings in SSE blocks", async () => {
    // Old Mac-style line endings
    const body = createSSEStream(["event: log\rdata: cr-payload\r\r"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const listenerFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.addEventListener("log", listenerFn);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(listenerFn).toHaveBeenCalledTimes(1);
    const event = listenerFn.mock.calls[0]![0] as MessageEvent;
    expect(event.type).toBe("log");
    expect(event.data).toBe("cr-payload");

    es.close();
  });

  it("handles mixed \\r\\n and \\n in the same block", async () => {
    const body = createSSEStream(["event: log\r\ndata: mixed\n\r\n"]);
    fetchSpy.mockResolvedValueOnce(
      new Response(body, { status: 200 })
    );

    const listenerFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.addEventListener("log", listenerFn);

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);

    expect(listenerFn).toHaveBeenCalledTimes(1);
    expect((listenerFn.mock.calls[0]![0] as MessageEvent).data).toBe("mixed");

    es.close();
  });

  // ─── Inactivity timeout tests ────────────────────────────────────────────

  it("reconnects after 45s of inactivity", async () => {
    // First response: holds the stream open forever (never closes, never sends data)
    let resolveHold: (() => void) | undefined;
    const holdingBody = new ReadableStream<Uint8Array>({
      start() {
        // Stream stays open, never enqueues or closes
      },
      pull() {
        // Return a promise that never resolves — simulates idle connection
        return new Promise<void>((r) => { resolveHold = r; });
      },
    });

    // Second fetch for the reconnect
    const reconnectBody = createSSEStream([]);

    fetchSpy
      .mockResolvedValueOnce(new Response(holdingBody, { status: 200 }))
      .mockResolvedValueOnce(new Response(reconnectBody, { status: 200 }));

    const errorFn = vi.fn();
    const openFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onerror = errorFn;
    es.onopen = openFn;

    // Let initial connect resolve
    await vi.advanceTimersByTimeAsync(0);
    expect(openFn).toHaveBeenCalledTimes(1);
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    // Advance 44s — should NOT have reconnected yet
    await vi.advanceTimersByTimeAsync(44000);
    expect(fetchSpy).toHaveBeenCalledTimes(1);

    // Advance past 45s — inactivity timer should fire, aborting fetch
    await vi.advanceTimersByTimeAsync(2000);

    // The abort causes the catch block → scheduleReconnect → onerror + 5s timer
    expect(errorFn).toHaveBeenCalled();

    // Resolve the held stream to avoid dangling promises
    resolveHold?.();

    // Advance 5s for the reconnect timer
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetchSpy).toHaveBeenCalledTimes(2);

    es.close();
  });

  it("resets inactivity timer on each data chunk", async () => {
    // Two chunks with a gap — first arrives at t=0, second at ~t=40s
    let sendSecondChunk: (() => void) | undefined;
    let closeStream: (() => void) | undefined;
    const encoder = new TextEncoder();

    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        // First chunk immediately
        controller.enqueue(encoder.encode("data: chunk1\n\n"));
        // Second chunk after external trigger
        new Promise<void>((r) => { sendSecondChunk = r; }).then(() => {
          controller.enqueue(encoder.encode("data: chunk2\n\n"));
          new Promise<void>((r) => { closeStream = r; }).then(() => {
            controller.close();
          });
        });
      },
    });

    fetchSpy.mockResolvedValueOnce(new Response(body, { status: 200 }));

    const msgFn = vi.fn();
    const errorFn = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onmessage = msgFn;
    es.onerror = errorFn;

    // Let connect + first chunk arrive
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(10);
    expect(msgFn).toHaveBeenCalledTimes(1);

    // Advance 40s — still within 45s window (timer was reset when chunk1 arrived)
    await vi.advanceTimersByTimeAsync(40000);
    expect(errorFn).not.toHaveBeenCalled();

    // Send second chunk at t≈40s — resets the timer
    sendSecondChunk?.();
    await vi.advanceTimersByTimeAsync(10);
    expect(msgFn).toHaveBeenCalledTimes(2);

    // Close cleanly
    closeStream?.();
    await vi.advanceTimersByTimeAsync(10);

    es.close();
  });
});
