import { describe, it, expect, vi, afterEach } from "vitest";
import { streamGroupDiscussion, type GroupSSEEvent } from "../groups";

/**
 * Framing tests for the group SSE reader.
 *
 * `readGroupSSE` is private, so these drive it through `streamGroupDiscussion`
 * with a stubbed `fetch` — which is also the honest level to test at, since the
 * bugs being pinned are all about how bytes arriving in awkward pieces get
 * reassembled.
 */

/** A response whose body yields exactly the given byte chunks, in order. */
function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    ok: true,
    status: 200,
    statusText: "OK",
    body: {
      getReader: () => ({
        read: async () =>
          i < chunks.length
            ? { done: false, value: encoder.encode(chunks[i++]) }
            : { done: true, value: undefined },
        releaseLock: () => {},
      }),
    },
  } as unknown as Response;
}

async function collect(chunks: string[]): Promise<GroupSSEEvent[]> {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(sseResponse(chunks)));
  const events: GroupSSEEvent[] = [];
  for await (const event of streamGroupDiscussion("g1", "question?")) {
    events.push(event);
  }
  return events;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("group SSE framing", () => {
  it("reads a well-formed frame", async () => {
    const events = await collect(["event: speaker_start\ndata: {\"a\":1}\n\n"]);
    expect(events).toEqual([{ type: "speaker_start", data: '{"a":1}' }]);
  });

  it("flushes the final frame when the server closes without a blank line", async () => {
    // Regression. The read loop used to `break` on done and drop whatever was
    // still buffered, so a server that ended on its last frame lost it — usually
    // group_complete, the event the UI waits for before it stops spinning.
    const events = await collect([
      "event: speaker_start\ndata: one\n\n",
      "event: group_complete\ndata: done",
    ]);
    expect(events.map((e) => e.type)).toEqual(["speaker_start", "group_complete"]);
    expect(events[1]!.data).toBe("done");
  });

  it("handles a CRLF split across two reads", async () => {
    // Regression. CRLF was normalised per decoded chunk, so a \r ending one
    // chunk and the \n opening the next were never paired, the frame boundary
    // was missed, and both events arrived mangled or not at all.
    const events = await collect([
      "event: a\r\ndata: first\r\n\r",
      "\nevent: b\r\ndata: second\r\n\r\n",
    ]);
    expect(events).toEqual([
      { type: "a", data: "first" },
      { type: "b", data: "second" },
    ]);
  });

  it("preserves meaningful whitespace in the payload", async () => {
    // Regression. Each data: line was .trim()ed, which destroys a token stream
    // where a lone space is a real chunk.
    const events = await collect(["event: token\ndata:  padded \n\n"]);
    expect(events[0]!.data).toBe(" padded ");
  });

  it("concatenates multi-line data with newlines, per the SSE spec", async () => {
    const events = await collect(["event: chunk\ndata: line1\ndata: line2\n\n"]);
    expect(events[0]!.data).toBe("line1\nline2");
  });

  it("skips a frame with no explicit event: line", async () => {
    // Deliberate divergence from the generic parser, preserved: group events
    // dispatch by name, so a nameless frame has nothing to dispatch on.
    const events = await collect(["data: orphan\n\nevent: real\ndata: x\n\n"]);
    expect(events).toEqual([{ type: "real", data: "x" }]);
  });

  it("ignores heartbeat comments", async () => {
    const events = await collect([": keep-alive\n\nevent: real\ndata: x\n\n"]);
    expect(events).toEqual([{ type: "real", data: "x" }]);
  });

  it("reassembles a frame delivered one byte at a time", async () => {
    const frame = "event: speaker_end\ndata: {\"ok\":true}\n\n";
    const events = await collect([...frame]);
    expect(events).toEqual([{ type: "speaker_end", data: '{"ok":true}' }]);
  });

  it("throws with the status when the stream is refused", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false, status: 403, statusText: "Forbidden" } as Response),
    );
    await expect(async () => {
      for await (const _ of streamGroupDiscussion("g1", "q")) {
        void _;
      }
    }).rejects.toThrow(/403/);
  });
});
