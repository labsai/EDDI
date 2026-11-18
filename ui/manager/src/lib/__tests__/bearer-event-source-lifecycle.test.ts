import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { BearerEventSource } from "@eddi/app-kit/lib/bearer-event-source";
import { SSE_RECONNECT_MAX_ATTEMPTS, SSE_RECONNECT_MAX_DELAY_MS } from "@eddi/app-kit/lib/constants";

/**
 * The review's four `BearerEventSource` defects (I10): an old connection that is
 * not ended before a new one starts, a throwing listener that leaks the reader, a
 * half event delivered after a drop, and a retry budget refilled by a few bytes
 * so that a server that sends some and drops is retried every five seconds for
 * good.
 */

function stream(chunks: string[], onCancel?: () => void): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  let index = 0;
  return new ReadableStream({
    pull(controller) {
      if (index < chunks.length) controller.enqueue(encoder.encode(chunks[index++]!));
      else controller.close();
    },
    cancel() {
      onCancel?.();
    },
  });
}

describe("BearerEventSource lifecycle", () => {
  let fetchSpy: MockInstance<typeof fetch>;

  beforeEach(() => {
    vi.useFakeTimers();
    fetchSpy = vi.spyOn(globalThis, "fetch");
    vi.spyOn(console, "error").mockImplementation(() => {});
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("a throwing listener does not stop the others or the stream, and the reader is released at the end", async () => {
    const cancel = vi.spyOn(ReadableStreamDefaultReader.prototype, "cancel");
    fetchSpy.mockResolvedValueOnce(new Response(stream(["event: log\ndata: one\n\nevent: log\ndata: two\n\n"]), { status: 200 }));
    fetchSpy.mockResolvedValue(new Response(null, { status: 503 }));
    const bad = vi.fn(() => {
      throw new Error("listener bug");
    });
    const good = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.addEventListener("log", bad);
    es.addEventListener("log", good);

    await vi.advanceTimersByTimeAsync(10);

    expect(bad).toHaveBeenCalledTimes(2);
    expect(good).toHaveBeenCalledTimes(2); // both events, past the throw
    expect(cancel).toHaveBeenCalled(); // the body is released when the read ends
    es.close();
  });

  it("releases the reader when the read is cut short by close()", async () => {
    const cancel = vi.spyOn(ReadableStreamDefaultReader.prototype, "cancel");
    fetchSpy.mockImplementationOnce(async (_url, init) => {
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          (init as RequestInit).signal?.addEventListener("abort", () => controller.error(new DOMException("aborted", "AbortError")));
        },
      });
      return new Response(body, { status: 200 });
    });
    const es = new BearerEventSource("http://test/sse");
    await vi.advanceTimersByTimeAsync(10);
    es.close();
    await vi.advanceTimersByTimeAsync(10);
    expect(cancel).toHaveBeenCalled();
  });

  it("does not deliver an unterminated block when the connection ends", async () => {
    fetchSpy.mockResolvedValueOnce(
      new Response(stream(["event: log\ndata: whole\n\n", 'event: log\ndata: {"half":']), { status: 200 }),
    );
    fetchSpy.mockResolvedValue(new Response(null, { status: 503 }));
    const log = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.addEventListener("log", log);

    await vi.advanceTimersByTimeAsync(10);

    expect(log).toHaveBeenCalledTimes(1);
    expect((log.mock.calls[0]![0] as MessageEvent).data).toBe("whole");
    es.close();
  });

  it("a server that sends a few bytes and drops does not refill the budget: it is given up on", async () => {
    fetchSpy.mockImplementation(async () => new Response(stream(["data: par"]), { status: 200 }));
    const exhausted = vi.fn();
    const es = new BearerEventSource("http://test/sse");
    es.onexhausted = exhausted;

    await vi.advanceTimersByTimeAsync(0);
    for (let i = 0; i < SSE_RECONNECT_MAX_ATTEMPTS; i++) {
      await vi.advanceTimersByTimeAsync(SSE_RECONNECT_MAX_DELAY_MS);
    }

    expect(exhausted).toHaveBeenCalledTimes(1);
    const calls = fetchSpy.mock.calls.length;
    expect(calls).toBe(SSE_RECONNECT_MAX_ATTEMPTS + 1);
    await vi.advanceTimersByTimeAsync(SSE_RECONNECT_MAX_DELAY_MS * 20);
    expect(fetchSpy).toHaveBeenCalledTimes(calls);
    es.close();
  });

  it("a heartbeat comment is a complete event: the budget refills", async () => {
    fetchSpy
      .mockResolvedValueOnce(new Response(null, { status: 502 }))
      .mockResolvedValueOnce(new Response(stream([": keep-alive\n\n"]), { status: 200 }))
      .mockResolvedValue(new Response(null, { status: 502 }));
    const es = new BearerEventSource("http://test/sse");
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(5000);
    await vi.advanceTimersByTimeAsync(0);
    const afterSuccess = fetchSpy.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000); // the BASE delay again
    expect(fetchSpy.mock.calls.length).toBeGreaterThan(afterSuccess);
    es.close();
  });

  it("a new connection ends the previous one, which neither reconnects nor delivers any more", async () => {
    const signals: AbortSignal[] = [];
    fetchSpy.mockImplementation(async (_url, init) => {
      signals.push((init as RequestInit).signal as AbortSignal);
      return new Response(new ReadableStream<Uint8Array>({ start() {} }), { status: 200 });
    });
    const es = new BearerEventSource("http://test/sse");
    await vi.advanceTimersByTimeAsync(10);
    expect(signals).toHaveLength(1);

    // A second connect while the first is alive (what an overlapping reconnect does).
    void (es as unknown as { connect: () => Promise<void> }).connect();
    await vi.advanceTimersByTimeAsync(10);

    expect(signals).toHaveLength(2);
    expect(signals[0]!.aborted).toBe(true);
    expect(signals[1]!.aborted).toBe(false);
    // The superseded attempt's abort is not a failure: nothing was scheduled.
    const calls = fetchSpy.mock.calls.length;
    await vi.advanceTimersByTimeAsync(SSE_RECONNECT_MAX_DELAY_MS * 3);
    expect(fetchSpy).toHaveBeenCalledTimes(calls);
    es.close();
  });
});
