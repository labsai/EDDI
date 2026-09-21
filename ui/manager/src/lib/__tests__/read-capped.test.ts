import { describe, expect, it } from "vitest";
import { readCapped } from "@/lib/read-capped";

describe("readCapped", () => {
  /** A body that records whether it was cancelled, and by whom. */
  function trackedBody(chunks: Uint8Array[]) {
    const state = { cancelled: false, pulls: 0 };
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        state.pulls += 1;
        const next = chunks.shift();
        if (next) controller.enqueue(next);
        else controller.close();
      },
      cancel() {
        state.cancelled = true;
      },
    });
    return { stream, state };
  }

  it("cancels the body when Content-Length alone exceeds the cap", async () => {
    // Rejecting on the header without touching the body left the stream
    // unlocked and unread, holding the connection open until GC.
    const { stream, state } = trackedBody([new Uint8Array(8)]);
    const response = new Response(stream, {
      headers: { "Content-Length": String(10 * 1024 * 1024) },
    });

    expect(await readCapped(response, 1024)).toBeNull();
    expect(state.cancelled).toBe(true);
    // Rejected on the header — the body was never pulled from.
    expect(state.pulls).toBe(0);
  });

  it("cancels the body when the streamed size passes the cap", async () => {
    const { stream, state } = trackedBody([
      new Uint8Array(600),
      new Uint8Array(600),
    ]);
    const response = new Response(stream);

    expect(await readCapped(response, 1024)).toBeNull();
    expect(state.cancelled).toBe(true);
  });

  it("returns the decoded body when it fits", async () => {
    const response = new Response(new TextEncoder().encode('{"openapi":"3.0.0"}'));
    expect(await readCapped(response, 1024)).toBe('{"openapi":"3.0.0"}');
  });

  it("accepts a body whose Content-Length is within the cap", async () => {
    const payload = new TextEncoder().encode("ok");
    const response = new Response(payload, {
      headers: { "Content-Length": String(payload.byteLength) },
    });
    expect(await readCapped(response, 1024)).toBe("ok");
  });

  it("ignores a missing or unparseable Content-Length and streams instead", async () => {
    const response = new Response(new TextEncoder().encode("body"), {
      headers: { "Content-Length": "not-a-number" },
    });
    expect(await readCapped(response, 1024)).toBe("body");
  });
});
